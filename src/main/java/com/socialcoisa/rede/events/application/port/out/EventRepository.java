package com.socialcoisa.rede.events.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.events.domain.EventAttendee;

public interface EventRepository {

	void save(Event event);

	Optional<Event> findById(String id);

	List<Event> findAll(String format, int limit);

	void saveAttendee(EventAttendee attendee);

	void removeAttendee(String eventId, String userId);

	boolean isAttending(String eventId, String userId);

	int countAttendees(String eventId);

}
