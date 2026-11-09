package com.socialcoisa.rede.shared.security;

import java.io.IOException;
import java.time.Clock;
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
 * Barreira central contra enumeração e scraping: um filtro, não uma regra por controller.
 *
 * <p>Lê a rota na {@link EnumerationSurface}, pontua o comportamento no
 * {@link EnumerationRiskEngine} e responde conforme o nível. A decisão de bloquear é sempre
 * <strong>temporária e progressiva</strong> — nunca ban permanente por heurística.</p>
 *
 * <p><b>Modo de operação.</b> {@code rede.security.enumeration.mode} aceita:</p>
 * <ul>
 * <li>{@code off} — não observa nada;</li>
 * <li>{@code shadow} (padrão) — observa, registra e expõe no painel, mas <strong>nunca nega</strong>.
 * É o modo para validar os limiares com tráfego real antes de ligar o bloqueio;</li>
 * <li>{@code enforce} — nega conforme o nível.</li>
 * </ul>
 *
 * <p><b>Chave da janela.</b> Identidade autenticada tem balde próprio ({@code u:}), porque quem
 * está logado não deve ser punido pelo vizinho de NAT. Sem sessão válida, a chave é o IP
 * ({@code ip:}) — e o IP vem do {@link ClientIpResolver}, que só confia em cabeçalho vindo de
 * proxy confiável.</p>
 *
 * <p><b>Bypass por troca de rota.</b> A janela é por chave, não por rota: pedir {@code /users/a},
 * depois {@code /users/a/presence}, depois {@code /users/a/profile} soma no mesmo balde. É o que
 * impede o script de reiniciar a contagem trocando de endpoint.</p>
 */
@Component
@Order(1)
public class EnumerationGuardFilter extends OncePerRequestFilter {

	private static final Logger LOG = LoggerFactory.getLogger(EnumerationGuardFilter.class);

	public enum Mode {
		OFF, SHADOW, ENFORCE
	}

	/** Bloqueio com prazo e episódio (a duração escala na reincidência). */
	private record Block(long until, int episode) {
	}

	private final EnumerationRiskEngine engine;
	private final ClientIpResolver ipResolver;
	private final RateLimitSessions sessions;
	private final Clock clock;

	private final Mode mode;
	private final long firstBlockMs;
	private final long maxBlockMs;
	private final Map<String, Block> blocks = new ConcurrentHashMap<>();

	public EnumerationGuardFilter(EnumerationRiskEngine engine, ClientIpResolver ipResolver,
			RateLimitSessions sessions, Clock clock,
			@Value("${rede.security.enumeration.mode:shadow}") String mode,
			@Value("${rede.security.enumeration.first-block-minutes:5}") long firstBlockMinutes,
			@Value("${rede.security.enumeration.max-block-minutes:1440}") long maxBlockMinutes) {
		this.engine = engine;
		this.ipResolver = ipResolver;
		this.sessions = sessions;
		this.clock = clock;
		this.mode = parseMode(mode);
		this.firstBlockMs = Math.max(1, firstBlockMinutes) * 60_000L;
		this.maxBlockMs = Math.max(this.firstBlockMs, maxBlockMinutes * 60_000L);
	}

	private static Mode parseMode(String value) {
		if (!StringUtils.hasText(value)) {
			return Mode.SHADOW;
		}
		try {
			return Mode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException unknown) {
			// Valor inválido cai no modo seguro: observar sem bloquear.
			return Mode.SHADOW;
		}
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (mode == Mode.OFF) {
			return true;
		}
		String uri = request.getRequestURI();
		// Sonda do cluster nunca entra: derrubar o health check derruba o pod.
		return uri.contains("/actuator") || uri.contains("/health");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String path = pathOf(request);
		EnumerationSurface.Route route = EnumerationSurface.match(path);
		if (route == null) {
			chain.doFilter(request, response);
			return;
		}

		String ip = ipResolver.resolve(request);
		String userId = authenticatedUser(request, ip);
		String key = userId != null ? "u:" + userId : "ip:" + ip;

		// Bloqueio ativo: nega antes de qualquer trabalho (só no modo enforce).
		if (mode == Mode.ENFORCE) {
			Block block = blocks.get(key);
			long now = clock.millis();
			if (block != null && block.until() > now) {
				deny(request, response, block.until() - now);
				return;
			}
		}

		chain.doFilter(request, response);

		// A pontuação usa o status real: 404 é o sinal mais forte de descoberta.
		var observation = new EnumerationRiskEngine.Observation(key, route.label(), route.kind(),
			EnumerationSurface.identifier(route, path), userId != null, response.getStatus());
		var assessment = engine.observe(observation);

		if (assessment.level() == EnumerationRiskEngine.Level.NONE) {
			return;
		}
		String event = eventFor(assessment);
		engine.log(event, key, route.label(), assessment);

		if (mode != Mode.ENFORCE) {
			// Shadow: o evento fica registrado e visível no painel; nada é negado.
			return;
		}
		if (assessment.atLeast(EnumerationRiskEngine.Level.CONFIRMED)) {
			applyBlock(key, assessment);
		}
	}

	/** Nome do evento de segurança, conforme o nível e o motivo dominante. */
	private static String eventFor(EnumerationRiskEngine.Assessment assessment) {
		return switch (assessment.level()) {
			case SUSPECT -> "USER_ENUMERATION_SUSPECTED";
			case HIGH -> assessment.sequential() ? "AUTOMATED_RESOURCE_DISCOVERY" : "API_SCRAPING_DETECTED";
			case CONFIRMED -> "USER_ENUMERATION_CONFIRMED";
			case SEVERE -> "AUTOMATED_IP_BAN";
			case NONE -> "USER_ENUMERATION_SUSPECTED";
		};
	}

	private void applyBlock(String key, EnumerationRiskEngine.Assessment assessment) {
		long now = clock.millis();
		Block previous = blocks.get(key);
		int episode = previous == null ? 1 : previous.episode() + 1;
		long duration = Math.min(maxBlockMs, firstBlockMs * (1L << Math.min(8, episode - 1)));
		blocks.put(key, new Block(now + duration, episode));
		engine.reset(key);
		LOG.warn("AUTOMATED_IP_BAN key={} episode={} durationMinutes={} score={} reasons={}", key, episode,
			duration / 60_000L, assessment.score(), assessment.reasons());
	}

	private void deny(HttpServletRequest request, HttpServletResponse response, long remainingMs)
			throws IOException {
		int seconds = (int) Math.max(1, (remainingMs + 999) / 1000);
		RateLimitResponses.send(request, response, seconds, "ENUMERATION_BLOCKED",
			"Consultas de descoberta bloqueadas temporariamente. Tente novamente mais tarde.");
	}

	/** Usuário da sessão, quando a sessão é válida para este IP; senão {@code null}. */
	private String authenticatedUser(HttpServletRequest request, String ip) {
		String session = request.getHeader("X-Session-Token");
		if (!StringUtils.hasText(session)) {
			return null;
		}
		return sessions.userId(session, ip);
	}

	/** Caminho sem query string e sem o prefixo {@code /api}, para casar com a superfície. */
	private static String pathOf(HttpServletRequest request) {
		String uri = request.getRequestURI();
		if (uri == null) {
			return "";
		}
		int query = uri.indexOf('?');
		String path = query >= 0 ? uri.substring(0, query) : uri;
		if (path.startsWith("/api/")) {
			path = path.substring(4);
		}
		else if (path.equals("/api")) {
			path = "/";
		}
		return path;
	}

	/** Bloqueios ativos agora (painel administrativo). */
	public Map<String, Long> activeBlocks() {
		long now = clock.millis();
		Map<String, Long> active = new java.util.LinkedHashMap<>();
		blocks.forEach((key, block) -> {
			if (block.until() > now) {
				active.put(key, block.until() - now);
			}
		});
		return active;
	}

	/** Libera uma chave manualmente (painel administrativo). */
	public boolean release(String key) {
		return blocks.remove(key) != null;
	}

	public Mode mode() {
		return mode;
	}
}
