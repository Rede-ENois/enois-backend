package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.messaging.domain.Conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "conversations")
class ConversationJpaEntity {

	@Id
	private String id;

	@Column(nullable = false)
	private String kind;

	private String title;

	@Column(name = "created_by", nullable = false)
	private String createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ConversationJpaEntity() {
	}

	static ConversationJpaEntity fromDomain(Conversation conversation) {
		ConversationJpaEntity e = new ConversationJpaEntity();
		e.id = conversation.id();
		e.kind = conversation.kind();
		e.title = conversation.title();
		e.createdBy = conversation.createdBy();
		e.createdAt = conversation.createdAt();
		e.updatedAt = conversation.updatedAt();
		return e;
	}

	Conversation toDomain() {
		return Conversation.restore(id, kind, title, createdBy, createdAt, updatedAt);
	}

}
