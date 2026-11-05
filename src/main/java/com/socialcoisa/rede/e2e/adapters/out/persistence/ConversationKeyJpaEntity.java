package com.socialcoisa.rede.e2e.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.e2e.domain.ConversationKey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "conversation_e2e_keys")
@IdClass(ConversationKeyId.class)
class ConversationKeyJpaEntity {

	@Id
	@Column(name = "conversation_id")
	private String conversationId;

	@Id
	@Column(name = "user_id")
	private String userId;

	@Column(name = "encrypted_key", nullable = false, columnDefinition = "text")
	private String encryptedKey;

	@Column(name = "key_version", nullable = false)
	private int keyVersion;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected ConversationKeyJpaEntity() {
	}

	ConversationKeyJpaEntity(ConversationKey key) {
		this.conversationId = key.conversationId();
		this.userId = key.userId();
		this.encryptedKey = key.encryptedKey();
		this.keyVersion = key.keyVersion();
		this.createdAt = key.createdAt();
	}

	ConversationKey toDomain() {
		return new ConversationKey(conversationId, userId, encryptedKey, keyVersion, createdAt);
	}
}
