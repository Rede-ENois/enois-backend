package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import com.socialcoisa.rede.messaging.domain.Message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "messages", indexes = @Index(name = "idx_messages_conversation_created", columnList = "conversation_id, created_at"))
class MessageJpaEntity {

	@Id
	private String id;

	@Column(name = "conversation_id", nullable = false)
	private String conversationId;

	@Column(name = "sender_id", nullable = false)
	private String senderId;

	@Column(nullable = false, length = 8000)
	private String text;

	@Column(name = "media_ids", length = 1000)
	private String mediaIds;

	@Column(name = "media_spoiler", nullable = false)
	private boolean mediaSpoiler;

	@Column(nullable = false)
	private boolean e2e;

	@Column(length = 44)
	private String iv;

	@Column(name = "reply_to_id")
	private String replyToId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected MessageJpaEntity() {
	}

	static MessageJpaEntity fromDomain(Message message) {
		MessageJpaEntity e = new MessageJpaEntity();
		e.id = message.id();
		e.conversationId = message.conversationId();
		e.senderId = message.senderId();
		e.text = message.text();
		e.mediaIds = message.mediaIds().isEmpty() ? null : String.join(",", message.mediaIds());
		e.mediaSpoiler = message.mediaSpoiler();
		e.e2e = message.e2e();
		e.iv = message.iv();
		e.replyToId = message.replyToId();
		e.createdAt = message.createdAt();
		return e;
	}

	Message toDomain() {
		List<String> media = mediaIds == null || mediaIds.isBlank()
			? List.of()
			: Arrays.stream(mediaIds.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
		if (e2e) {
			return Message.restoreE2e(id, conversationId, senderId, text, iv, media, mediaSpoiler, replyToId, createdAt);
		}
		return Message.restore(id, conversationId, senderId, text, media, mediaSpoiler, replyToId, createdAt);
	}

}
