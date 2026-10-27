package com.socialcoisa.rede.shared.crypto;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Guarda as chaves de canal em memória, com prazo e teto.
 *
 * <p>Sem banco de propósito: a chave de canal é descartável por definição. Se o processo reiniciar,
 * os canais caem e o cliente faz o handshake de novo — mais barato e mais seguro do que persistir
 * material de chave. O teto evita que um cliente mal-comportado (ou um ataque) faça a memória crescer
 * sem limite: ao encher, a sessão mais antiga sai.</p>
 */
@Component
public class CryptoSessionRegistry {

	private final Map<String, CryptoSession> sessions = new ConcurrentHashMap<>();
	private final Clock clock;
	private final int maxSessions;
	private final AtomicLong created = new AtomicLong();

	public CryptoSessionRegistry(Clock clock,
			@Value("${rede.crypto.max-sessions:10000}") int maxSessions) {
		this.clock = clock;
		this.maxSessions = Math.max(1, maxSessions);
	}

	public void put(CryptoSession session) {
		purgeExpired();
		if (sessions.size() >= maxSessions) {
			evictOldestExpiring();
		}
		sessions.put(session.id(), session);
		created.incrementAndGet();
	}

	/** Sessão válida ou vazio (inexistente e vencida são a mesma resposta para quem chama). */
	public java.util.Optional<CryptoSession> find(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			return java.util.Optional.empty();
		}
		CryptoSession session = sessions.get(sessionId);
		if (session == null) {
			return java.util.Optional.empty();
		}
		if (session.isExpired(clock.instant())) {
			sessions.remove(sessionId);
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(session);
	}

	public int size() {
		return sessions.size();
	}

	/** Total já criado — útil para métrica, sem revelar nada sobre as chaves. */
	long createdTotal() {
		return created.get();
	}

	void purgeExpired() {
		Instant now = clock.instant();
		sessions.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
	}

	private void evictOldestExpiring() {
		sessions.entrySet().stream()
			.min(java.util.Comparator.comparing(entry -> entry.getValue().expiresAt()))
			.map(Map.Entry::getKey)
			.ifPresent(sessions::remove);
	}
}
