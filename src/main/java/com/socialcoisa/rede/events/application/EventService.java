package com.socialcoisa.rede.events.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.events.application.port.out.EventRepository;
import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.events.domain.EventAttendee;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class EventService {

	private final EventRepository repository;
	private final Clock clock;

	public EventService(EventRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional
	public Event create(String title, String date, String time, String format, String host, String cover,
			String userId) {
		String id = "event_" + UUID.randomUUID().toString().replace("-", "");
		Event event = Event.create(id, title, date, time, format, host, cover, userId, clock.instant());
		repository.save(event);
		return event;
	}

	@Transactional
	public void attend(String eventId, String userId) {
		repository.findById(eventId)
			.orElseThrow(() -> new ApiException(404, "EVENT_NOT_FOUND", "Evento nao encontrado."));
		if (repository.isAttending(eventId, userId)) {
			throw new ApiException(409, "ALREADY_ATTENDING", "Voce ja confirmou presenca.");
		}
		repository.saveAttendee(EventAttendee.attend(eventId, userId, clock.instant()));
	}

	@Transactional
	public void cancelAttendance(String eventId, String userId) {
		if (!repository.isAttending(eventId, userId)) {
			throw new ApiException(404, "NOT_ATTENDING", "Voce nao esta inscrito neste evento.");
		}
		repository.removeAttendee(eventId, userId);
	}

	public List<Event> list(String format, java.time.Instant before, int limit) {
		return repository.findAll(format, before, limit);
	}

	public Event getById(String id) {
		return repository.findById(id)
			.orElseThrow(() -> new ApiException(404, "EVENT_NOT_FOUND", "Evento nao encontrado."));
	}

	public int countAttendees(String eventId) {
		return repository.countAttendees(eventId);
	}

}
