package com.socialcoisa.rede.events.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.shared.geo.CityState;
import com.socialcoisa.rede.events.domain.EventRecurrence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "events", indexes = {@Index(name = "idx_events_format", columnList = "format"),
	@Index(name = "idx_events_state_city", columnList = "state, city_key")})
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

	@Column(name = "stream_url", length = 500)
	private String streamUrl;

	@Column(length = 255)
	private String location;

	@Column(length = 80)
	private String city;

	@Column(length = 2)
	private String state;

	/** Cidade sem acento e minuscula (ver CityState.key): e por ela que o filtro compara. */
	@Column(name = "city_key", length = 80)
	private String cityKey;

	@Column(name = "created_by", nullable = false)
	private String createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "sanitized_at")
	private Instant sanitizedAt;

	@Column(name = "final_attendees_count")
	private Integer finalAttendeesCount;

	@Column(nullable = false, length = 16)
	private String recurrence = EventRecurrence.NONE.name();

	@Column(name = "series_id")
	private String seriesId;

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
		e.streamUrl = event.streamUrl();
		e.location = event.location();
		if (event.place() != null) {
			e.city = event.place().city();
			e.state = event.place().state();
			e.cityKey = event.place().cityKey();
		}
		e.createdBy = event.createdBy();
		e.createdAt = event.createdAt();
		e.updatedAt = event.updatedAt();
		e.sanitizedAt = event.sanitizedAt();
		e.finalAttendeesCount = event.finalAttendeesCount();
		e.recurrence = event.recurrence().name();
		e.seriesId = event.seriesId();
		return e;
	}

	Event toDomain() {
		Event event = Event.restore(id, title, date, time, format, host, cover, streamUrl, location, createdBy,
			createdAt, updatedAt);
		if (city != null && state != null) {
			event.placeAt(new CityState(city, state, cityKey));
		}
		if (sanitizedAt != null) {
			event.markSanitized(finalAttendeesCount == null ? 0 : finalAttendeesCount, sanitizedAt);
		}
		event.changeRecurrence(EventRecurrence.valueOf(recurrence), seriesId);
		return event;
	}

}
