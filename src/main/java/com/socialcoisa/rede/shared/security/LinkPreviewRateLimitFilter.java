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
 * Teto de leitura da miniatura de link, por IP e por sessão.
 *
 * <p>Um limitador no controlador seria do processo: com N pods o teto real vira N vezes o
 * configurado, e o balde é um só para todo mundo. Aqui cada pessoa tem o seu: quem está logado usa
 * o da sessão, quem não está divide o do IP. Um atacante que escolhe endereços nunca vistos fura o
 * cache — este teto é o que impede que ele escolha também quantas conexões de saída e quantas
 * consultas o servidor faz por minuto.</p>
 *
 * <p>Este filtro conta TODA chamada (servida ou não do cache): o atacante não sabe qual endereço
 * já está em cache, então contar só as leituras externas daria a ele uma cota ilimitada de varredura
 * de cache. Janela deslizante em memória, como os outros limitadores do projeto.</p>
 */
@Component
@Order(2)
public class LinkPreviewRateLimitFilter extends OncePerRequestFilter {

	private static final Logger LOG = LoggerFactory.getLogger(LinkPreviewRateLimitFilter.class);

	private static final long WINDOW_MS = 60_000L;

	private static final int MAX_KEYS = 100_000;

	private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

	private final Clock clock;

	private final ClientIpResolver ipResolver;

	private final RateLimitSessions sessions;

	private final int perMinute;

	public LinkPreviewRateLimitFilter(Clock clock, ClientIpResolver ipResolver, RateLimitSessions sessions,
			@Value("${rede.rate.link-preview-per-minute:40}") int perMinute) {
		this.clock = clock;
		this.ipResolver = ipResolver;
		this.sessions = sessions;
		this.perMinute = Math.max(1, perMinute);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"GET".equalsIgnoreCase(request.getMethod())
			|| !"/link-preview".equals(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long now = clock.millis();
		String ip = ipResolver.resolve(request);
		String key = "ip:" + ip;
		String who = key;
		String session = request.getHeader("X-Session-Token");
		if (StringUtils.hasText(session)) {
			String userId = sessions.userId(session, ip);
			if (userId != null) {
				key = "u:" + userId;
				who = "sessao";
			}
		}
		Deque<Long> marks = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (marks) {
			while (!marks.isEmpty() && marks.peekFirst() < now - WINDOW_MS) {
				marks.pollFirst();
			}
			if (marks.size() >= perMinute) {
				LOG.warn("Miniatura de link acima do teto (chave={})", who);
				RateLimitResponses.send(request, response, 60, "LINK_PREVIEW_GUARD",
					"Muitas miniaturas de link em pouco tempo. Tente de novo em instantes.");
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
