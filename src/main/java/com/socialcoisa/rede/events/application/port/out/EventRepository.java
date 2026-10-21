package com.socialcoisa.rede.events.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.events.domain.EventAttendee;

public interface EventRepository {

	void save(Event event);

	Optional<Event> findById(String id);

	List<Event> findAll(String format, java.time.Instant before, int limit);

	void saveAttendee(EventAttendee attendee);

	void removeAttendee(String eventId, String userId);

	boolean isAttending(String eventId, String userId);

	/** Event ids from {@code eventIds} where the user has confirmed attendance (batch). */
	java.util.Set<String> findAttendingEventIds(String userId, java.util.Collection<String> eventIds);

	int countAttendees(String eventId);

	/** Eventos que ainda nao passaram pela sanitizacao (o vencimento e avaliado no dominio). */
	List<Event> findNotSanitized();

	/** Apaga as presencas de um evento (dado pessoal) sem apagar o evento. */
	void deleteAttendees(String eventId);

	/** Ids das pessoas presentes — lido ANTES da purga para preservar a contribuição. */
	List<String> findAttendeeIds(String eventId);

	/** Soma 1 na contribuição PERMANENTE de presenças (o que sobrevive à purga de PII). */
	void addAttendanceToTotal(String userId);

	/** Eventos sanitizados antes do instante informado (expurgo por retencao). */
	List<Event> findSanitizedBefore(java.time.Instant threshold);

	void deleteById(String eventId);

}
