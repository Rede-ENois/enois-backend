package com.socialcoisa.rede.e2e.domain;

import java.time.Instant;

public record UserKeypair(String id, String userId, String publicKey, String encryptedPrivateKey,
		String algorithm, boolean active, Instant createdAt) {
}
