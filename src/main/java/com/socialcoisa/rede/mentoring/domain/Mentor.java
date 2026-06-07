package com.socialcoisa.rede.mentoring.domain;

import java.time.Instant;
import java.util.List;

public class Mentor {

	private final String id;
	private final String userId;
	private final List<String> expertise;
	private final double rating;
	private final int sessionsCount;
	private final String price;
	private final Instant createdAt;
	private final Instant updatedAt;

	private Mentor(String id, String userId, List<String> expertise, double rating, int sessionsCount, String price,
			Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.userId = userId;
		this.expertise = expertise;
		this.rating = rating;
		this.sessionsCount = sessionsCount;
		this.price = price;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static Mentor create(String id, String userId, List<String> expertise, String price, Instant now) {
		return new Mentor(id, userId, List.copyOf(expertise), 0.0, 0, price, now, now);
	}

	public static Mentor restore(String id, String userId, List<String> expertise, double rating, int sessionsCount,
			String price, Instant createdAt, Instant updatedAt) {
		return new Mentor(id, userId, expertise, rating, sessionsCount, price, createdAt, updatedAt);
	}

	public Mentor update(List<String> expertise, String price, Instant now) {
		return new Mentor(id, userId, expertise, rating, sessionsCount, price, createdAt, now);
	}

	public String id() { return id; }

	public String userId() { return userId; }

	public List<String> expertise() { return expertise; }

	public double rating() { return rating; }

	public int sessionsCount() { return sessionsCount; }

	public String price() { return price; }

	public Instant createdAt() { return createdAt; }

	public Instant updatedAt() { return updatedAt; }

}
