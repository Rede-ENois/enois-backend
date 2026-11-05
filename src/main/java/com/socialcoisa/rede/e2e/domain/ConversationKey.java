package com.socialcoisa.rede.e2e.domain;

import java.time.Instant;

public record ConversationKey(String conversationId, String userId, String encryptedKey,
		int keyVersion, Instant createdAt) {
}
