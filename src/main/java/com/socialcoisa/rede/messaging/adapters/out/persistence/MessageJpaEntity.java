package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.time.Instant;

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

	@Column(nullable = false, length = 5000)
	private String text;

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
		e.createdAt = message.createdAt();
		return e;
	}

	Message toDomain() {
		return Message.restore(id, conversationId, senderId, text, createdAt);
	}

}
