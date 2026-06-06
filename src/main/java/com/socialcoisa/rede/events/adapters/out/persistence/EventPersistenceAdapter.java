package com.socialcoisa.rede.events.adapters.out.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.events.application.port.out.EventRepository;
import com.socialcoisa.rede.events.domain.Event;

@Repository
class EventPersistenceAdapter implements EventRepository {

	private final SpringDataEventJpaRepository springData;
	private final SpringDataEventAttendeeJpaRepository attendeeRepo;
	private final Clock clock;

	EventPersistenceAdapter(SpringDataEventJpaRepository springData,
			SpringDataEventAttendeeJpaRepository attendeeRepo, Clock clock) {
		this.springData = springData;
		this.attendeeRepo = attendeeRepo;
		this.clock = clock;
	}

	@Override
	public void save(Event event) {
		springData.save(EventJpaEntity.fromDomain(event));
	}

	@Override
	public Optional<Event> findById(String id) {
		return springData.findById(id).map(EventJpaEntity::toDomain);
	}

	@Override
	public List<Event> findAll(String format, Instant before, int limit) {
		PageRequest page = PageRequest.of(0, limit);
		List<EventJpaEntity> entities;
		if (StringUtils.hasText(format)) {
			entities = springData.findByFormat(format, before, page);
		} else {
			entities = springData.findAllBefore(before, page);
		}
		return entities.stream().map(EventJpaEntity::toDomain).toList();
	}

	@Override
	public void saveAttendee(com.socialcoisa.rede.events.domain.EventAttendee attendee) {
		attendeeRepo.save(EventAttendeeJpaEntity.create(attendee.eventId(), attendee.userId(), clock.instant()));
	}

	@Override
	public void removeAttendee(String eventId, String userId) {
		attendeeRepo.deleteByEventIdAndUserId(eventId, userId);
	}

	@Override
	public boolean isAttending(String eventId, String userId) {
		return attendeeRepo.existsByEventIdAndUserId(eventId, userId);
	}

	@Override
	public int countAttendees(String eventId) {
		return attendeeRepo.countByEventId(eventId);
	}

}
