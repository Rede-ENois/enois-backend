package com.socialcoisa.rede.events.domain;

import java.time.Instant;

public class Event {

	private final String id;
	private final String title;
	private final String date;
	private final String time;
	private final String format;
	private final String host;
	private final String cover;
	private final String createdBy;
	private final Instant createdAt;
	private final Instant updatedAt;

	private Event(String id, String title, String date, String time, String format, String host, String cover,
			String createdBy, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.title = title;
		this.date = date;
		this.time = time;
		this.format = format;
		this.host = host;
		this.cover = cover;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static Event create(String id, String title, String date, String time, String format, String host,
			String cover, String createdBy, Instant now) {
		return new Event(id, title, date, time, format, host, cover, createdBy, now, now);
	}

	public static Event restore(String id, String title, String date, String time, String format, String host,
			String cover, String createdBy, Instant createdAt, Instant updatedAt) {
		return new Event(id, title, date, time, format, host, cover, createdBy, createdAt, updatedAt);
	}

	public String id() { return id; }

	public String title() { return title; }

	public String date() { return date; }

	public String time() { return time; }

	public String format() { return format; }

	public String host() { return host; }

	public String cover() { return cover; }

	public String createdBy() { return createdBy; }

	public Instant createdAt() { return createdAt; }

	public Instant updatedAt() { return updatedAt; }

}
