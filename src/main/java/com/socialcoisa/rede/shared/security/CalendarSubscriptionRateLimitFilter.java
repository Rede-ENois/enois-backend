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
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Teto de leitura da assinatura privada da agenda (`/calendar/subscribe/{token}.ics`, D7).
 *
 * <p>Esta é a única rota do calendário que responde sem sessão, e o token no caminho é o que
 * separa as agendas. Um token inventado não chega a ser um risco de vazamento — é o hash que
 * decide —, mas uma varredura de tokens é tráfego e trabalho de banco de graça. O limite é por
 * token (hash do que veio no caminho) e, quando a origem é o que sobra, por IP: assim um leitor
 * externo legítimo revalidando de hora em hora não é punido, e quem martela um caminho é.
 *
 * <p>Chave pelo hash do token, nunca o token: a mesma regra do restante do `shared.security`, que
 * não guarda credencial em memória de limite nem em log.
 */
@Component
@Order(2)
public class CalendarSubscriptionRateLimitFilter extends OncePerRequestFilter {

	private static final Logger LOG = LoggerFactory.getLogger(CalendarSubscriptionRateLimitFilter.class);

	private static final long WINDOW_MS = 60_000L;
	private static final int MAX_KEYS = 100_000;
	private static final String PREFIX = "/calendar/subscribe/";

	private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
	private final Clock clock;
	private final ClientIpResolver ipResolver;
	private final int perMinute;

	public CalendarSubscriptionRateLimitFilter(Clock clock, ClientIpResolver ipResolver,
			@Value("${rede.rate.calendar-subscription-per-minute:60}") int perMinute) {
		this.clock = clock;
		this.ipResolver = ipResolver;
		this.perMinute = Math.max(1, perMinute);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"GET".equalsIgnoreCase(request.getMethod())
			|| !request.getRequestURI().startsWith(PREFIX);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long now = clock.millis();
		String key = keyOf(request);
		Deque<Long> marks = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (marks) {
			while (!marks.isEmpty() && marks.peekFirst() < now - WINDOW_MS) {
				marks.pollFirst();
			}
			if (marks.size() >= perMinute) {
				LOG.warn("Assinatura de agenda acima do teto de leitura");
				RateLimitResponses.send(request, response, 60, "CALENDAR_SUBSCRIPTION_GUARD",
					"Muitas leituras da assinatura em pouco tempo. Tente de novo em instantes.");
				return;
			}
			marks.addLast(now);
		}
		if (hits.size() > MAX_KEYS) {
			hits.entrySet().removeIf(entry -> entry.getValue().isEmpty());
		}
		chain.doFilter(request, response);
	}

	/** O identificador do token no caminho, hasheado — o token em claro não fica em memória. */
	private String keyOf(HttpServletRequest request) {
		String path = request.getRequestURI();
		String token = path.substring(PREFIX.length());
		if (token.endsWith(".ics")) {
			token = token.substring(0, token.length() - 4);
		}
		if (token.isBlank()) {
			return "ip:" + ipResolver.resolve(request);
		}
		return "t:" + Hashes.sha256Hex(token);
	}
}