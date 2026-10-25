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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rate limit GLOBAL de escrita para toda a API — a última linha de defesa contra varredura, flood e
 * scripts que martelam endpoints variados (os limites por usuário e por ação ficam nos guards
 * específicos; este é o teto de superfície inteira).
 *
 * <p>Chave por usuário autenticado (hash do token, nunca em claro); sem sessão, cai no IP. Assim um
 * atacante sem conta não consome a cota de quem está logado — e quem está logado tem teto próprio,
 * não dividido com a rede inteira atrás do mesmo proxy. Endpoints de sonda (actuator) passam fora:
 * derrubar o health check derruba o pod.</p>
 */
@Component
@Order(1)
public class GlobalApiRateLimitFilter extends OncePerRequestFilter {

	private static final Logger LOG = LoggerFactory.getLogger(GlobalApiRateLimitFilter.class);

	private static final int MAX_KEYS = 100_000;
	private static final long WINDOW_MS = 60_000L;

	private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
	private final Clock clock;
	private final ClientIpResolver clientIpResolver;
	private final int perMinute;
	private final int sharedMultiplier;

	public GlobalApiRateLimitFilter(Clock clock, ClientIpResolver clientIpResolver,
			@Value("${rede.rate.global-per-minute:0}") int perMinute,
			@Value("${rede.rate.global-shared-multiplier:50}") int sharedMultiplier) {
		this.clock = clock;
		this.clientIpResolver = clientIpResolver;
		this.perMinute = perMinute;
		this.sharedMultiplier = Math.max(1, sharedMultiplier);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String uri = request.getRequestURI();
		if (uri.contains("/actuator") || uri.contains("/health")) {
			return true;
		}
		// Leitura nao entra no teto. Uma tela do app dispara varias chamadas ao abrir e o feed se
		// atualiza sozinho: punir GET transformava navegacao normal em 429. O que precisa de teto
		// e a ESCRITA (POST/PUT/PATCH/DELETE), que e onde ha custo e abuso possivel.
		String metodo = request.getMethod();
		return "GET".equalsIgnoreCase(metodo) || "HEAD".equalsIgnoreCase(metodo)
			|| "OPTIONS".equalsIgnoreCase(metodo);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (perMinute <= 0) {
			chain.doFilter(request, response);
			return;
		}
	// Sessão tem teto próprio; sem sessão, o IP com multiplicador (tráfego interno do
	// cluster atrás do ingress não tem header e dividia o balde com todo mundo).
	String session = request.getHeader("X-Session-Token");
	String key;
	int limit;
	if (StringUtils.hasText(session)) {
		key = "u:" + com.socialcoisa.rede.shared.security.Hashes.sha256Hex(session.trim());
		limit = perMinute;
	}
	else {
		boolean hasHeader = StringUtils.hasText(request.getHeader("X-Forwarded-For"))
			|| StringUtils.hasText(request.getHeader("X-Real-IP"));
		key = "ip:" + clientIpResolver.resolve(request);
		limit = hasHeader ? perMinute : perMinute * sharedMultiplier;
	}

		long now = clock.millis();
		if (hits.size() > MAX_KEYS) {
			sweep(now);
		}
		Deque<Long> timestamps = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
		boolean allowed;
		synchronized (timestamps) {
			while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= WINDOW_MS) {
				timestamps.pollFirst();
			}
			if (timestamps.size() >= limit) {
				allowed = false;
			}
			else {
				timestamps.addLast(now);
				allowed = true;
			}
		}
		if (!allowed) {
			LOG.warn("Rate limit global atingido (chave: {})", key);
			RateLimitResponses.send(request, response, 60, "RATE_LIMITED",
				"Muitas requisicoes. Tente novamente em instantes.");
			return;
		}
		chain.doFilter(request, response);
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
