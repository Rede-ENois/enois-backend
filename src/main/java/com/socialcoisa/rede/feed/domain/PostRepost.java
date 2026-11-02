package com.socialcoisa.rede.feed.domain;

import java.time.Instant;

/** Repost puro: "user X republicou post Y". Nao cria post novo o post original aparece no feed de X com "Repostado por X". Likes e comentarios ficam no original. **/

public class PostRepost {

	private final Long id;
	private final String userId;
	private final String postId;
	private final Instant createdAt;

	private PostRepost(Long id, String userId, String postId, Instant createdAt) {
		this.id = id;
		this.userId = userId;
		this.postId = postId;
		this.createdAt = createdAt;
	}

	public static PostRepost create(String userId, String postId, Instant now) {
		return new PostRepost(null, userId, postId, now);
	}

	public static PostRepost restore(Long id, String userId, String postId, Instant createdAt) {
		return new PostRepost(id, userId, postId, createdAt);
	}

	public Long id() { return id; }
	public String userId() { return userId; }
	public String postId() { return postId; }
	public Instant createdAt() { return createdAt; }
}
