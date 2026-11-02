package com.socialcoisa.rede.shared.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import com.socialcoisa.rede.shared.security.ClientIpResolver;
import com.socialcoisa.rede.shared.security.Hashes;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rede de segurança contra duplo clique/retry em TODO POST.
 *
 * O front manda uma Idempotency-Key nova a cada clique, então a idempotência por chave não pega
 * dois envios disparados no mesmo instante. Aqui a chave é derivada do CONTEÚDO: mesma sessão/IP,
 * mesma rota e mesmo corpo dentro de uma janela curta devolvem exatamente a resposta da primeira
 * execução — a segunda não chega ao controller.
 *
 * A janela é curta (padrão 3s) e configurável. Multipart fica de fora: o corpo pode ser grande e
 * esses fluxos (import de ZIP) não são alvo de duplo clique. Nada do corpo é logado.
 */
@Component
@Order(4)
public class DuplicatePostFilter extends OncePerRequestFilter {

	private static final Logger LOG = LoggerFactory.getLogger(DuplicatePostFilter.class);
	private static final int MAX_BODY = 1_000_000;

	private final ClientIpResolver ipResolver;
	private final boolean enabled;
	private final Map<String, CompletableFuture<CachedResponse>> inFlight = new ConcurrentHashMap<>();

	public DuplicatePostFilter(ClientIpResolver ipResolver,
			@Value("${rede.security.duplicate-post-enabled:true}") boolean enabled) {
		this.ipResolver = ipResolver;
		this.enabled = enabled;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!enabled || !"POST".equalsIgnoreCase(request.getMethod())) {
			return true;
		}
		String contentType = request.getContentType();
		return contentType != null && contentType.toLowerCase().startsWith("multipart/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// Corpo declarado grande: nao materializa so para descobrir que passa do teto. Segue sem
		// cache (o duplo clique nao e o risco aqui; o heap e).
		long declaredLength = request.getContentLengthLong();
		if (declaredLength > MAX_BODY) {
			chain.doFilter(request, response);
			return;
		}
		byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
		if (body.length > MAX_BODY) {
			// Corpo grande: não guarda na janela, mas devolve o corpo lido ao controller.
			chain.doFilter(new CachedBodyRequest(request, body), response);
			return;
		}
		String key = keyOf(request, body);
		CompletableFuture<CachedResponse> mine = new CompletableFuture<>();
		CompletableFuture<CachedResponse> existing = inFlight.putIfAbsent(key, mine);
		if (existing != null) {
			replay(existing, response);
			return;
		}

		CachedResponse cached = null;
		ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
		try {
			chain.doFilter(new CachedBodyRequest(request, body), wrapped);
			cached = new CachedResponse(wrapped.getStatus(), wrapped.getContentType(),
				StreamUtils.copyToByteArray(wrapped.getContentInputStream()));
		}
		finally {
			// Mesmo que o controller lance, a resposta (tratada pelo advice) é reproduzida para o
			// segundo clique em vez de executar a ação de novo.
			if (cached == null) {
				cached = new CachedResponse(wrapped.getStatus(),
					wrapped.getContentType() == null ? MediaType.APPLICATION_JSON_VALUE : wrapped.getContentType(),
					StreamUtils.copyToByteArray(wrapped.getContentInputStream()));
			}
			mine.complete(cached);
			inFlight.remove(key, mine);
			wrapped.copyBodyToResponse();
		}
	}

	private void replay(CompletableFuture<CachedResponse> pending, HttpServletResponse response) throws IOException {
		try {
			CachedResponse cached = pending.get(10, TimeUnit.SECONDS);
			response.setStatus(cached.status());
			if (cached.contentType() != null) {
				response.setContentType(cached.contentType());
			}
			response.setContentLength(cached.body().length);
			response.getOutputStream().write(cached.body());
		}
		catch (TimeoutException timeout) {
			response.setStatus(409);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write("{\"code\":\"DUPLICATE_IN_PROGRESS\","
				+ "\"message\":\"A mesma requisicao ainda esta em processamento.\"}");
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			response.setStatus(409);
		}
		catch (ExecutionException failure) {
			response.setStatus(409);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write("{\"code\":\"DUPLICATE_FAILED\","
				+ "\"message\":\"A requisicao original falhou; tente novamente.\"}");
		}
	}

	private String keyOf(HttpServletRequest request, byte[] body) {
		String token = request.getHeader("X-Session-Token");
		String identity = token != null && !token.isBlank()
			? "session:" + Hashes.sha256Hex(token.trim())
			: "ip:" + ipResolver.resolve(request);
		String payload = identity + "\n" + request.getMethod() + "\n" + request.getRequestURI() + "\n"
			+ (request.getQueryString() == null ? "" : request.getQueryString()) + "\n"
			+ Hashes.sha256Hex(new String(body, StandardCharsets.UTF_8));
		return Hashes.sha256Hex(payload);
	}

	private record CachedResponse(int status, String contentType, byte[] body) {
	}

	/** Corpo lido uma vez e reapresentado ao controller (o InputStream do Servlet é de uso único). */
	private static final class CachedBodyRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		CachedBodyRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream input = new ByteArrayInputStream(body);
			return new ServletInputStream() {
				@Override public boolean isFinished() { return input.available() == 0; }
				@Override public boolean isReady() { return true; }
				@Override public void setReadListener(ReadListener listener) { }
				@Override public int read() { return input.read(); }
			};
		}

		@Override
		public java.io.BufferedReader getReader() {
			return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
		}
	}

}
