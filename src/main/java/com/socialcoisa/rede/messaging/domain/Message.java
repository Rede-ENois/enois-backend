package com.socialcoisa.rede.messaging.domain;

import java.time.Instant;
import java.util.List;

public class Message {

	private final String id;
	private final String conversationId;
	private final String senderId;
	private final String text;
	private final List<String> mediaIds;
	private final boolean mediaSpoiler;
	private final boolean e2e;
	private final String iv;
	private final String replyToId;
	private final Instant createdAt;

	private Message(String id, String conversationId, String senderId, String text, List<String> mediaIds,
			Instant createdAt, boolean mediaSpoiler, boolean e2e, String iv, String replyToId) {
		this.id = id;
		this.conversationId = conversationId;
		this.senderId = senderId;
		this.text = text;
		this.mediaIds = mediaIds == null ? List.of() : List.copyOf(mediaIds);
		this.mediaSpoiler = mediaSpoiler;
		this.e2e = e2e;
		this.iv = iv;
		this.replyToId = replyToId;
		this.createdAt = createdAt;
	}

	public static Message create(String id, String conversationId, String senderId, String text, Instant now) {
		return new Message(id, conversationId, senderId, text, List.of(), now, false, false, null, null);
	}

	public static Message create(String id, String conversationId, String senderId, String text,
			List<String> mediaIds, Instant now) {
		return new Message(id, conversationId, senderId, text, mediaIds, now, false, false, null, null);
	}

	public static Message create(String id, String conversationId, String senderId, String text,
			List<String> mediaIds, boolean mediaSpoiler, Instant now) {
		return new Message(id, conversationId, senderId, text, mediaIds, now, mediaSpoiler, false, null, null);
	}

	public static Message create(String id, String conversationId, String senderId, String text,
			List<String> mediaIds, boolean mediaSpoiler, String replyToId, Instant now) {
		return new Message(id, conversationId, senderId, text, mediaIds, now, mediaSpoiler, false, null, replyToId);
	}

	public static Message restore(String id, String conversationId, String senderId, String text, Instant createdAt) {
		return new Message(id, conversationId, senderId, text, List.of(), createdAt, false, false, null, null);
	}

	public static Message restore(String id, String conversationId, String senderId, String text,
			List<String> mediaIds, Instant createdAt) {
		return new Message(id, conversationId, senderId, text, mediaIds, createdAt, false, false, null, null);
	}

	public static Message restore(String id, String conversationId, String senderId, String text,
			List<String> mediaIds, boolean mediaSpoiler, Instant createdAt) {
		return new Message(id, conversationId, senderId, text, mediaIds, createdAt, mediaSpoiler, false, null, null);
	}

	public static Message restore(String id, String conversationId, String senderId, String text,
			List<String> mediaIds, boolean mediaSpoiler, String replyToId, Instant createdAt) {
		return new Message(id, conversationId, senderId, text, mediaIds, createdAt, mediaSpoiler, false, null, replyToId);
	}

	public static Message createE2e(String id, String conversationId, String senderId, String ciphertext,
			String iv, List<String> mediaIds, boolean mediaSpoiler, Instant now) {
		return new Message(id, conversationId, senderId, ciphertext, mediaIds, now, mediaSpoiler, true, iv, null);
	}

	public static Message createE2e(String id, String conversationId, String senderId, String ciphertext,
			String iv, List<String> mediaIds, boolean mediaSpoiler, String replyToId, Instant now) {
		return new Message(id, conversationId, senderId, ciphertext, mediaIds, now, mediaSpoiler, true, iv, replyToId);
	}

	public static Message restoreE2e(String id, String conversationId, String senderId, String ciphertext,
			String iv, List<String> mediaIds, boolean mediaSpoiler, Instant createdAt) {
		return new Message(id, conversationId, senderId, ciphertext, mediaIds, createdAt, mediaSpoiler, true, iv, null);
	}

	public static Message restoreE2e(String id, String conversationId, String senderId, String ciphertext,
			String iv, List<String> mediaIds, boolean mediaSpoiler, String replyToId, Instant createdAt) {
		return new Message(id, conversationId, senderId, ciphertext, mediaIds, createdAt, mediaSpoiler, true, iv, replyToId);
	}

	public String id() { return id; }

	public String conversationId() { return conversationId; }

	public String senderId() { return senderId; }

	public String text() { return text; }

	/** Ate 4 midias anexadas; o texto pode vir vazio quando ha midia. */
	public List<String> mediaIds() { return mediaIds; }

	public boolean mediaSpoiler() { return mediaSpoiler; }

	public boolean e2e() { return e2e; }

	public String iv() { return iv; }

	public String replyToId() { return replyToId; }

	public Instant createdAt() { return createdAt; }

}
