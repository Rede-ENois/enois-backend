package com.socialcoisa.rede.messaging.domain;

import java.time.Instant;

public class ConversationParticipant {

	private final Long id;
	private final String conversationId;
	private final String userId;
	private final Instant lastReadAt;
	private final Instant joinedAt;

	private ConversationParticipant(Long id, String conversationId, String userId, Instant lastReadAt, Instant joinedAt) {
		this.id = id;
		this.conversationId = conversationId;
		this.userId = userId;
		this.lastReadAt = lastReadAt;
		this.joinedAt = joinedAt;
	}

	public static ConversationParticipant create(String conversationId, String userId, Instant now) {
		return new ConversationParticipant(null, conversationId, userId, null, now);
	}

	public static ConversationParticipant restore(Long id, String conversationId, String userId, Instant lastReadAt,
			Instant joinedAt) {
		return new ConversationParticipant(id, conversationId, userId, lastReadAt, joinedAt);
	}

	public ConversationParticipant markRead(Instant now) {
		return new ConversationParticipant(id, conversationId, userId, now, joinedAt);
	}

	public Long id() { return id; }

	public String conversationId() { return conversationId; }

	public String userId() { return userId; }

	public Instant lastReadAt() { return lastReadAt; }

	public Instant joinedAt() { return joinedAt; }

}
