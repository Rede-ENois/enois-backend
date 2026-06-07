package com.socialcoisa.rede.profile.domain;

import java.time.Instant;

public class Recommendation {

	private final String id;
	private final String recommenderId;
	private final String recommendedId;
	private final String comment;
	private final Instant createdAt;

	private Recommendation(String id, String recommenderId, String recommendedId, String comment, Instant createdAt) {
		this.id = id;
		this.recommenderId = recommenderId;
		this.recommendedId = recommendedId;
		this.comment = comment;
		this.createdAt = createdAt;
	}

	public static Recommendation create(String id, String recommenderId, String recommendedId, String comment,
			Instant now) {
		return new Recommendation(id, recommenderId, recommendedId, comment, now);
	}

	public static Recommendation restore(String id, String recommenderId, String recommendedId, String comment,
			Instant createdAt) {
		return new Recommendation(id, recommenderId, recommendedId, comment, createdAt);
	}

	public String id() { return id; }
	public String recommenderId() { return recommenderId; }
	public String recommendedId() { return recommendedId; }
	public String comment() { return comment; }
	public Instant createdAt() { return createdAt; }
}
