package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.messaging.domain.ConversationParticipant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "conversation_participants",
	uniqueConstraints = @UniqueConstraint(name = "uk_conversation_participant", columnNames = { "conversation_id",
			"user_id" }),
	indexes = {
			@Index(name = "idx_conversation_participants_user", columnList = "user_id"),
			@Index(name = "idx_conversation_participants_conversation", columnList = "conversation_id") })
class ConversationParticipantJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "conversation_id", nullable = false)
	private String conversationId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "last_read_at")
	private Instant lastReadAt;

	@Column(name = "joined_at", nullable = false)
	private Instant joinedAt;

	protected ConversationParticipantJpaEntity() {
	}

	static ConversationParticipantJpaEntity fromDomain(ConversationParticipant participant) {
		ConversationParticipantJpaEntity e = new ConversationParticipantJpaEntity();
		e.id = participant.id();
		e.conversationId = participant.conversationId();
		e.userId = participant.userId();
		e.lastReadAt = participant.lastReadAt();
		e.joinedAt = participant.joinedAt();
		return e;
	}

	Instant getLastReadAt() {
		return lastReadAt;
	}

	void markRead(Instant now) {
		this.lastReadAt = now;
	}

	ConversationParticipant toDomain() {
		return ConversationParticipant.restore(id, conversationId, userId, lastReadAt, joinedAt);
	}

}
