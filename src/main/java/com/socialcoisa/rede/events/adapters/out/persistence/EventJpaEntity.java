package com.socialcoisa.rede.events.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.events.domain.Event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "events", indexes = @Index(name = "idx_events_format", columnList = "format"))
class EventJpaEntity {

	@Id
	private String id;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false)
	private String date;

	@Column(nullable = false)
	private String time;

	@Column(nullable = false)
	private String format;

	@Column(nullable = false)
	private String host;

	@Column(length = 500)
	private String cover;

	@Column(name = "created_by", nullable = false)
	private String createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected EventJpaEntity() {
	}

	static EventJpaEntity fromDomain(Event event) {
		EventJpaEntity e = new EventJpaEntity();
		e.id = event.id();
		e.title = event.title();
		e.date = event.date();
		e.time = event.time();
		e.format = event.format();
		e.host = event.host();
		e.cover = event.cover();
		e.createdBy = event.createdBy();
		e.createdAt = event.createdAt();
		e.updatedAt = event.updatedAt();
		return e;
	}

	Event toDomain() {
		return Event.restore(id, title, date, time, format, host, cover, createdBy, createdAt, updatedAt);
	}

}
