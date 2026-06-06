package com.socialcoisa.rede.messaging.domain;

import java.time.Instant;

public class Message {

	private final String id;
	private final String conversationId;
	private final String senderId;
	private final String text;
	private final Instant createdAt;

	private Message(String id, String conversationId, String senderId, String text, Instant createdAt) {
		this.id = id;
		this.conversationId = conversationId;
		this.senderId = senderId;
		this.text = text;
		this.createdAt = createdAt;
	}

	public static Message create(String id, String conversationId, String senderId, String text, Instant now) {
		return new Message(id, conversationId, senderId, text, now);
	}

	public static Message restore(String id, String conversationId, String senderId, String text, Instant createdAt) {
		return new Message(id, conversationId, senderId, text, createdAt);
	}

	public String id() { return id; }

	public String conversationId() { return conversationId; }

	public String senderId() { return senderId; }

	public String text() { return text; }

	public Instant createdAt() { return createdAt; }

}
