package com.socialcoisa.rede.e2e.adapters.out.persistence;

import java.io.Serializable;
import java.util.Objects;

class ConversationKeyId implements Serializable {
	private String conversationId;
	private String userId;

	ConversationKeyId() {
	}

	ConversationKeyId(String conversationId, String userId) {
		this.conversationId = conversationId;
		this.userId = userId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ConversationKeyId that)) return false;
		return Objects.equals(conversationId, that.conversationId) && Objects.equals(userId, that.userId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(conversationId, userId);
	}
}
