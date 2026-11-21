package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.messaging.domain.ConversationTopic;
import com.socialcoisa.rede.messaging.domain.ConversationTopicStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "conversation_group_topics")
class ConversationTopicJpaEntity {

	@Id
	@Column(length = 64)
	private String id;

	@Column(name = "conversation_id", nullable = false)
	private String conversationId;

	@Column(nullable = false, length = 80)
	private String name;

	@Column(nullable = false)
	private int position;

	@Column(nullable = false, length = 16)
	private String status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ConversationTopicJpaEntity() {
	}

	static ConversationTopicJpaEntity fromDomain(ConversationTopic topic) {
		ConversationTopicJpaEntity e = new ConversationTopicJpaEntity();
		e.id = topic.id();
		e.conversationId = topic.conversationId();
		e.name = topic.name();
		e.position = topic.position();
		e.status = topic.status().name();
		e.createdAt = topic.createdAt();
		e.updatedAt = topic.updatedAt();
		return e;
	}

	ConversationTopic toDomain() {
		// Status desconhecido vira ARCHIVED: um valor corrompido nunca reabre o topico para escrita.
		ConversationTopicStatus stored = "OPEN".equalsIgnoreCase(status) ? ConversationTopicStatus.OPEN
			: ConversationTopicStatus.ARCHIVED;
		return ConversationTopic.restore(id, conversationId, name, position, stored, createdAt, updatedAt);
	}

}
