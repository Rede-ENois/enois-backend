package com.socialcoisa.rede.communities.domain;

import java.time.Instant;

public class Community {

	private final String id;
	private final String name;
	private final String description;
	private final String topic;
	private final String cover;
	private final String createdBy;
	private final Instant createdAt;
	private final Instant updatedAt;

	private Community(String id, String name, String description, String topic, String cover, String createdBy,
			Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.name = name;
		this.description = description;
		this.topic = topic;
		this.cover = cover;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static Community create(String id, String name, String description, String topic, String cover,
			String createdBy, Instant now) {
		return new Community(id, name, description, topic, cover, createdBy, now, now);
	}

	public static Community restore(String id, String name, String description, String topic, String cover,
			String createdBy, Instant createdAt, Instant updatedAt) {
		return new Community(id, name, description, topic, cover, createdBy, createdAt, updatedAt);
	}

	public Community update(String name, String description, String topic, String cover, Instant now) {
		return new Community(id, name, description, topic, cover, createdBy, createdAt, now);
	}

	public String id() { return id; }

	public String name() { return name; }

	public String description() { return description; }

	public String topic() { return topic; }

	public String cover() { return cover; }

	public String createdBy() { return createdBy; }

	public Instant createdAt() { return createdAt; }

	public Instant updatedAt() { return updatedAt; }

}
