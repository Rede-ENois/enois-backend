package com.socialcoisa.rede.shared.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.socialcoisa.rede.shared.web.TrustedOriginCors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Decifra o corpo das requisições nas rotas marcadas — fail-closed.
 *
 * <p>Se a rota está na lista e o corpo não vem cifrado num canal válido, a resposta é erro explícito:
 * aceitar o corpo em claro "para não quebrar" transformaria a proteção em enfeite, porque bastaria
 * omitir o cabeçalho.</p>
 *
 * <p><strong>Convenção de AAD</strong> (o cliente precisa montar igual, senão não abre):
 * {@code REQ <MÉTODO> <CAMINHO> <timestamp> <nonce>}. Isso amarra o corpo à rota e à requisição: mover
 * o corpo para outra rota, ou repetir a mesma requisição, falha.</p>
 *
 * <p><strong>Limites desta etapa:</strong> só a <em>requisição</em> é decifrada; a resposta ainda vai em
 * claro e a cifra dela entra no próximo incremento. Requisição sem corpo (GET) não é afetada mesmo em
 * rota marcada.</p>
 */
@Component
@Order(4)
public class CryptoChannelFilter extends OncePerRequestFilter {

	public static final String SESSION_HEADER = "X-Crypto-Session";
	public static final String TIMESTAMP_HEADER = "X-Crypto-Timestamp";
	public static final String NONCE_HEADER = "X-Crypto-Nonce";

	private static final long MAX_CLOCK_SKEW_SECONDS = 120;

	private final CryptoSessionRegistry registry;
	private final Set<String> protectedPaths;

	public CryptoChannelFilter(CryptoSessionRegistry registry,
			@Value("${rede.crypto.protected-paths:}") String protectedPaths) {
		this.registry = registry;
		this.protectedPaths = new LinkedHashSet<>(Arrays.stream(protectedPaths.split(","))
			.map(String::trim)
			.filter(path -> !path.isEmpty())
			.toList());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String path = applicationPath(request);
		if (!isProtected(path, request.getMethod())) {
			chain.doFilter(request, response);
			return;
		}
		if (isBodyless(request)) {
			// GET/HEAD/OPTIONS em rota marcada: nada a decifrar.
			chain.doFilter(request, response);
			return;
		}

		String sessionId = request.getHeader(SESSION_HEADER);
		java.util.Optional<CryptoSession> session = registry.find(sessionId);
		if (session.isEmpty()) {
			reject(request, response, 409, "CRYPTO_CHANNEL_REQUIRED",
				"Esta requisicao precisa de um canal cifrado. Abra o canal e tente de novo.");
			return;
		}

		String timestamp = request.getHeader(TIMESTAMP_HEADER);
		String nonce = request.getHeader(NONCE_HEADER);
		if (!withinWindow(timestamp)) {
			reject(request, response, 409, "CRYPTO_TIMESTAMP_INVALID",
				"Requisicao fora da janela de tempo. Sincronize o relogio e tente de novo.");
			return;
		}
		if (!registry.acceptNonce(sessionId, nonce)) {
			reject(request, response, 409, "CRYPTO_REPLAY", "Requisicao repetida recusada. Monte uma nova.");
			return;
		}

		String envelope = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		if (envelope.isEmpty()) {
			reject(request, response, 400, "CRYPTO_ENVELOPE_REQUIRED", "Corpo cifrado obrigatorio nesta rota.");
			return;
		}
		String aad = "REQ " + request.getMethod() + " " + path + " " + timestamp + " " + nonce;
		String body;
		try {
			body = new EnvelopeCipher(new CryptoSessionKeyProvider(session.get())).decrypt(envelope, aad);
		}
		catch (CryptoException failure) {
			reject(request, response, 400, failure.code(), failure.getMessage());
			return;
		}

		chain.doFilter(new EnvelopeRequest(request, body.getBytes(StandardCharsets.UTF_8)), response);
	}

	/** Caminho como o app enxerga (o gateway tira o prefixo `/api`). */
	static String applicationPath(HttpServletRequest request) {
		String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
		String context = request.getContextPath() == null ? "" : request.getContextPath();
		String path = context.isEmpty() ? uri : uri.substring(context.length());
		return path.startsWith("/api/") ? path.substring(4) : path;
	}

	private boolean isProtected(String path, String method) {
		if (protectedPaths.isEmpty()) {
			return false;
		}
		return protectedPaths.contains(path) || protectedPaths.contains(method + " " + path);
	}

	private static boolean isBodyless(HttpServletRequest request) {
		String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(java.util.Locale.ROOT);
		return method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS");
	}

	private static boolean withinWindow(String timestamp) {
		if (timestamp == null || timestamp.isBlank()) {
			return false;
		}
		try {
			long sent = Long.parseLong(timestamp.trim());
			long now = java.time.Instant.now().getEpochSecond();
			return Math.abs(now - sent) <= MAX_CLOCK_SKEW_SECONDS;
		}
		catch (NumberFormatException failure) {
			return false;
		}
	}

	/**
	 * Recusa com corpo JSON e os cabeçalhos de CORS da origem confiável.
	 *
	 * <p>Sem o CORS aqui o navegador esconde o status: o front (que mora em outro host, tanto em
	 * produção quanto na QA) não conseguiria ler {@code CRYPTO_CHANNEL_REQUIRED} para abrir o canal e
	 * repetir — veria só "falha de rede". O gate de origem já correu antes deste filtro e marcou a
	 * origem; {@link TrustedOriginCors#apply} reflete só a marcada.</p>
	 */
	private static void reject(HttpServletRequest request, HttpServletResponse response, int status, String code,
			String message) throws IOException {
		TrustedOriginCors.apply(request, response);
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		String safe = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
		response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + safe + "\"}");
	}

	/** Requisição com o corpo já decifrado, para o controller ler como JSON normal. */
	static final class EnvelopeRequest extends jakarta.servlet.http.HttpServletRequestWrapper {

		private final byte[] body;

		EnvelopeRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public jakarta.servlet.ServletInputStream getInputStream() {
			java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(body);
			return new jakarta.servlet.ServletInputStream() {
				@Override
				public int read() {
					return stream.read();
				}

				@Override
				public boolean isFinished() {
					return stream.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(jakarta.servlet.ReadListener listener) {
					// Corpo já está em memória: leitura assíncrona não se aplica.
				}
			};
		}

		@Override
		public java.io.BufferedReader getReader() {
			return new java.io.BufferedReader(new java.io.InputStreamReader(
				new java.io.ByteArrayInputStream(body), StandardCharsets.UTF_8));
		}

		@Override
		public int getContentLength() {
			return body.length;
		}

		@Override
		public long getContentLengthLong() {
			return body.length;
		}
	}
}
