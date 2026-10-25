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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Teto de leitura nas rotas públicas de perfil e busca de pessoas.
 *
 * O limite global (300/min por IP) é generoso por desenho — a API inteira passa por ele. Este é
 * mais apertado na superfície que serve para ENUMERAR contas: a busca de pessoas (`/users/search`)
 * e a leitura de perfil (`/users/{username}`). Um humano abre poucos perfis por minuto; um script
 * varrendo a base bate no teto em segundos e recebe 429 ENUMERATION_GUARD.
 *
 * Janela deslizante em memória por IP, como os outros limitadores do projeto.
 */
@Component
@Order(2)
public class PublicReadRateLimitFilter extends OncePerRequestFilter {

	private static final Logger LOG = LoggerFactory.getLogger(PublicReadRateLimitFilter.class);
	private static final long WINDOW_MS = 60_000L;
	private static final int MAX_KEYS = 100_000;

	private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
	private final Clock clock;
	private final ClientIpResolver ipResolver;
	private final int perMinute;

	public PublicReadRateLimitFilter(Clock clock, ClientIpResolver ipResolver,
			@Value("${rede.rate.public-read-per-minute:600}") int perMinute) {
		this.clock = clock;
		this.ipResolver = ipResolver;
		// O valor configurado vale como esta: o piso de 10 antigo ignorava o teste e qualquer
		// ajuste de operacao abaixo disso.
		this.perMinute = Math.max(1, perMinute);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!"GET".equalsIgnoreCase(request.getMethod())) {
			return true;
		}
		String path = request.getRequestURI();
		return !(path.equals("/users/search") || path.startsWith("/users/"));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// Logado tem balde próprio (sessão); anônimo divide o do IP — é a enumeração anônima
		// que este teto existe para conter, não o usuário navegando entre perfis.
		String session = request.getHeader("X-Session-Token");
		String key;
		String who;
		if (org.springframework.util.StringUtils.hasText(session)) {
			key = "u:" + com.socialcoisa.rede.shared.security.Hashes.sha256Hex(session.trim());
			who = "sessao";
		}
		else {
			key = "ip:" + ipResolver.resolve(request);
			who = key;
		}
		long now = clock.millis();
		Deque<Long> marks = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (marks) {
			while (!marks.isEmpty() && marks.peekFirst() < now - WINDOW_MS) {
				marks.pollFirst();
			}
			if (marks.size() >= perMinute) {
				LOG.warn("Leitura pública acima do teto (chave={}, rota={})", who, request.getRequestURI());
				RateLimitResponses.send(request, response, 60, "ENUMERATION_GUARD",
					"Muitas consultas de perfis em pouco tempo. Tente de novo em instantes.");
				return;
			}
			marks.addLast(now);
		}
		if (hits.size() > MAX_KEYS) {
			hits.entrySet().removeIf(entry -> entry.getValue().isEmpty());
		}
		chain.doFilter(request, response);
	}
}
