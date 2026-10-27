package com.socialcoisa.rede.shared.security;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Teto específico para POSTs — a superfície que cria estado (publicar, comentar, curtir, seguir,
 * enviar mensagem, criar conta, denunciar...). O limite global por IP já existe, mas ele não vê
 * quem está autenticado: uma conta abusando de vários IPs (ou de um proxy) passaria.
 *
 * Chave dupla: token de sessão (hash, nunca o token em claro) e IP. Ambos precisam estar dentro
 * do teto. É janela deslizante em memória, por pod, como os outros limitadores do projeto.
 * GET/HEAD/OPTIONS não passam por aqui; actuator fica de fora para não derrubar health check.
 */
@Component
@Order(3)
public class PostRateLimitFilter extends OncePerRequestFilter {

	private static final Logger LOG = LoggerFactory.getLogger(PostRateLimitFilter.class);
	private static final long WINDOW_MS = 60_000L;
	private static final int MAX_KEYS = 100_000;

	private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
	private final Clock clock;
	private final ClientIpResolver ipResolver;
	private final boolean enabled;
	private final int userLimit;
	private final int ipLimit;

	public PostRateLimitFilter(Clock clock, ClientIpResolver ipResolver,
			@Value("${rede.rate.post-guard-enabled:true}") boolean enabled,
			@Value("${rede.rate.post-user-per-minute:120}") int userLimit,
			@Value("${rede.rate.post-ip-per-minute:300}") int ipLimit) {
		this.clock = clock;
		this.ipResolver = ipResolver;
		this.enabled = enabled;
		this.userLimit = userLimit;
		this.ipLimit = ipLimit;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!enabled || !"POST".equalsIgnoreCase(request.getMethod())) {
			return true;
		}
		String uri = request.getRequestURI();
		return uri.contains("/actuator") || uri.contains("/health");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// Logado: teto pelo hash da sessão (120/min). Anônimo: teto pelo IP (300/min).
		// Nunca os dois juntos — usuário logado não herda rate limit de outra pessoa no mesmo IP.
		String token = request.getHeader("X-Session-Token");
		boolean allowed;
		if (StringUtils.hasText(token)) {
			allowed = tryAcquire("u:" + Hashes.sha256Hex(token.trim()), userLimit);
		}
		else {
			allowed = tryAcquire("i:" + ipResolver.resolve(request), ipLimit);
		}
		if (!allowed) {
			LOG.warn("Rate limit de POST atingido");
			RateLimitResponses.send(request, response, 60, "RATE_LIMITED",
				"Muitas acoes em pouco tempo. Espere um minuto e tente de novo.");
			return;
		}
		chain.doFilter(request, response);
	}

	private boolean tryAcquire(String key, int limit) {
		if (limit <= 0) {
			return true;
		}
		long now = clock.millis();
		if (hits.size() > MAX_KEYS) {
			sweep(now);
		}
		Deque<Long> timestamps = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
		synchronized (timestamps) {
			while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= WINDOW_MS) {
				timestamps.pollFirst();
			}
			if (timestamps.size() >= limit) {
				return false;
			}
			timestamps.addLast(now);
			return true;
		}
	}

	private void sweep(long now) {
		hits.entrySet().removeIf(entry -> {
			Deque<Long> timestamps = entry.getValue();
			synchronized (timestamps) {
				while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= WINDOW_MS) {
					timestamps.pollFirst();
				}
				return timestamps.isEmpty();
			}
		});
	}

}
