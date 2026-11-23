package com.socialcoisa.rede.messaging.domain;

import java.time.Instant;

public class ConversationParticipant {

	private final Long id;
	private final String conversationId;
	private final String userId;
	private final ConversationRole role;
	private final Instant lastReadAt;
	private final Instant joinedAt;

	private ConversationParticipant(Long id, String conversationId, String userId, ConversationRole role,
			Instant lastReadAt, Instant joinedAt) {
		this.id = id;
		this.conversationId = conversationId;
		this.userId = userId;
		this.role = role == null ? ConversationRole.MEMBER : role;
		this.lastReadAt = lastReadAt;
		this.joinedAt = joinedAt;
	}

	public static ConversationParticipant create(String conversationId, String userId, Instant now) {
		return new ConversationParticipant(null, conversationId, userId, ConversationRole.MEMBER, null, now);
	}

	public static ConversationParticipant restore(Long id, String conversationId, String userId, ConversationRole role,
			Instant lastReadAt, Instant joinedAt) {
		return new ConversationParticipant(id, conversationId, userId, role, lastReadAt, joinedAt);
	}

	public ConversationParticipant markRead(Instant now) {
		return new ConversationParticipant(id, conversationId, userId, role, now, joinedAt);
	}

	public Long id() { return id; }

	public String conversationId() { return conversationId; }

	public String userId() { return userId; }

	/** Papel gravado na linha: ADMIN ou MEMBER. O dono e derivado do criador da conversa. */
	public ConversationRole role() { return role; }

	public Instant lastReadAt() { return lastReadAt; }

	public Instant joinedAt() { return joinedAt; }

}
