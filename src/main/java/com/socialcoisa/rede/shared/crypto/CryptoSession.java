package com.socialcoisa.rede.shared.crypto;

import java.time.Instant;

/**
 * Chave simétrica efêmera de um canal de aplicação (cliente ⇄ servidor).
 *
 * <p>Vive só em memória, com prazo: não vai para banco nem para log. O identificador pode aparecer
 * em cabeçalho; a chave, nunca.</p>
 */
public record CryptoSession(String id, byte[] key, Instant expiresAt) {

	public boolean isExpired(Instant now) {
		return !now.isBefore(expiresAt);
	}
}
