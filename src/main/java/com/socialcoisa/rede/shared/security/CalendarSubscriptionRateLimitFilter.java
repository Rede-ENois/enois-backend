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
 * decide —, mas uma varredura de tokens é tráfego e trabalho de banco de graça.
 *
 * <p>São dois tetos, com papéis diferentes. O <b>do token</b> (hash do que veio no caminho) protege
 * um link específico de quem martela o mesmo endereço. O <b>da origem</b> contém a varredura: sem
 * ele, cada token inventado ganha um balde novo e quem varia o caminho nunca bate em teto nenhum.
 * O teto da origem é folgado de propósito, porque um serviço de calendário legítimo busca de um
 * punhado de endereços em nome de muita gente; ele existe para cortar varredura, não para regular
 * quem assinou.
 *
 * <p>Chave pelo hash do token, nunca o token: a mesma regra do restante do `shared.security`, que
 * não guarda credencial em memória de limite nem em log. O IP também não aparece em log.
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
	private final int perOriginPerMinute;

	public CalendarSubscriptionRateLimitFilter(Clock clock, ClientIpResolver ipResolver,
			@Value("${rede.rate.calendar-subscription-per-minute:60}") int perMinute,
			@Value("${rede.rate.calendar-subscription-origin-per-minute:600}") int perOriginPerMinute) {
		this.clock = clock;
		this.ipResolver = ipResolver;
		this.perMinute = Math.max(1, perMinute);
		this.perOriginPerMinute = Math.max(1, perOriginPerMinute);
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
		// A origem primeiro: um varredor não pode nem chegar a criar balde por token.
		if (exceeded("o:" + ipResolver.resolve(request), perOriginPerMinute, now)) {
			reject(request, response);
			return;
		}
		String token = tokenOf(request);
		if (!token.isBlank() && exceeded("t:" + Hashes.sha256Hex(token), perMinute, now)) {
			reject(request, response);
			return;
		}
		chain.doFilter(request, response);
	}

	private static void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
		LOG.warn("Assinatura de agenda acima do teto de leitura");
		RateLimitResponses.send(request, response, 60, "CALENDAR_SUBSCRIPTION_GUARD",
			"Muitas leituras da assinatura em pouco tempo. Tente de novo em instantes.");
	}

	private boolean exceeded(String key, int limit, long now) {
		if (hits.size() > MAX_KEYS) {
			sweep(now);
		}
		Deque<Long> marks = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (marks) {
			while (!marks.isEmpty() && marks.peekFirst() < now - WINDOW_MS) {
				marks.pollFirst();
			}
			if (marks.size() >= limit) {
				return true;
			}
			marks.addLast(now);
			return false;
		}
	}

	/** Descarta as chaves que já venceram; roda só quando o mapa cresce demais. */
	private void sweep(long now) {
		hits.entrySet().removeIf(entry -> {
			Deque<Long> marks = entry.getValue();
			synchronized (marks) {
				while (!marks.isEmpty() && marks.peekFirst() < now - WINDOW_MS) {
					marks.pollFirst();
				}
				return marks.isEmpty();
			}
		});
	}

	/** O token do caminho, sem o `.ics`. */
	private static String tokenOf(HttpServletRequest request) {
		String token = request.getRequestURI().substring(PREFIX.length());
		return token.endsWith(".ics") ? token.substring(0, token.length() - 4) : token;
	}
}