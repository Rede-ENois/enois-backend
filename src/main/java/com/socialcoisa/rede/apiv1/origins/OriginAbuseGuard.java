package com.socialcoisa.rede.apiv1.origins;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Derruba automaticamente uma origem de TERCEIRO quando ela acumula respostas de recusa
 * (401/403/429) em uma janela curta — o padrão de quem sonda endpoints ou usa credencial roubada.
 *
 * A origem do PRÓPRIO APP nunca é derrubada: os usuários da plataforma compartilham a mesma
 * origem, então bloqueá-la tiraria o site de todos. Foi o que derrubou o Énóis inteiro em
 * 23/10 — o ataque veio da própria origem do app, o guard contou as recusas do ATACANTE
 * junto com as dos usuários e bloqueou a origem de todo mundo.
 *
 * O que impede a repetição: as origens do app ({@link AppOriginRegistry} — a mesma lista do
 * portão e do CORS) são consideradas confiáveis aqui e nunca acumulam bloqueio — só origem de
 * TERCEIRO cadastrada pode ser derrubada.
 */
@Component
class OriginAbuseGuard {

	private static final Logger LOG = LoggerFactory.getLogger(OriginAbuseGuard.class);

	private final DeveloperOriginService origins;
	/** Origem do proprio app: a mesma lista que o portao e o CORS usam (fonte unica). */
	private final AppOriginRegistry appOrigins;
	private final int maxFailures;
	private final long windowMs;
	private final long blockMinutes;
	private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

	OriginAbuseGuard(DeveloperOriginService origins, AppOriginRegistry appOrigins,
			@Value("${rede.origins.suspicious.max-failures:60}") int maxFailures,
			@Value("${rede.origins.suspicious.window-seconds:300}") long windowSeconds,
			@Value("${rede.origins.suspicious.block-minutes:30}") long blockMinutes) {
		this.origins = origins;
		this.appOrigins = appOrigins;
		this.maxFailures = maxFailures;
		this.windowMs = Math.max(1, windowSeconds) * 1000;
		this.blockMinutes = Math.max(1, blockMinutes);
	}

	void recordFailure(String origin) {
		if (maxFailures <= 0 || origin == null || origin.isBlank()) {
			return;
		}
		// Origem do próprio app (usuários da plataforma compartilham a origem): o tráfego
		//legítimo dela (expiração de sessão gera 401 natural) nunca pode fechá-la.
		if (appOrigins.isTrusted(origin.trim().toLowerCase(java.util.Locale.ROOT))) {
			return;
		}
		long now = System.currentTimeMillis();
		Deque<Long> window = failures.computeIfAbsent(origin, key -> new ArrayDeque<>());
		int count;
		synchronized (window) {
			while (!window.isEmpty() && now - window.peekFirst() > windowMs) {
				window.pollFirst();
			}
			window.addLast(now);
			count = window.size();
			if (count < maxFailures) {
				return;
			}
		}
		failures.remove(origin);
		try {
			origins.block(origin, "Atividade suspeita: " + count + " respostas 401/403/429 em "
				+ (windowMs / 1000) + "s", java.time.Duration.ofMinutes(blockMinutes));
		}
		catch (RuntimeException failure) {
			LOG.warn("origin_block_failed origin={} error={}", origin, failure.getClass().getSimpleName());
		}
	}

	/** Devolve à allowlist as origens cujo bloqueio temporário venceu. */
	@Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
	void releaseExpired() {
		try {
			origins.releaseExpired();
		}
		catch (RuntimeException failure) {
			LOG.warn("origin_block_release_failed error={}", failure.getClass().getSimpleName());
		}
	}

	/** Evita que origens com poucas falhas fiquem na memória para sempre. */
	@Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
	void cleanup() {
		long now = System.currentTimeMillis();
		failures.entrySet().removeIf(entry -> {
			Deque<Long> window = entry.getValue();
			synchronized (window) {
				return window.isEmpty() || now - window.peekLast() > windowMs;
			}
		});
	}
}
