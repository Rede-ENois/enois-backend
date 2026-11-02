package com.socialcoisa.rede.events.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.events.domain.EventAttendee;

public interface EventRepository {

	void save(Event event);

	Optional<Event> findById(String id);

	/** `state` nulo desliga o filtro de lugar; com `state`, so eventos nao-online daquela UF/cidade. */
	List<Event> findAll(String format, String state, String cityKey, java.time.Instant before, boolean recurringOnly,
			int limit);

	/** Every event whose series still repeats (recurrence other than NONE). */
	List<Event> findRecurring();

	/** The most recently created occurrence of a series: only it spawns the next one. */
	Optional<Event> findLatestInSeries(String seriesId);

	boolean existsInSeries(String seriesId, String date);

	/** Recurrence is a property of the whole series: changing it (or stopping it) hits every occurrence. */
	void updateSeriesRecurrence(String seriesId, com.socialcoisa.rede.events.domain.EventRecurrence recurrence);

	void saveAttendee(EventAttendee attendee);

	void removeAttendee(String eventId, String userId);

	boolean isAttending(String eventId, String userId);

	/** Event ids from {@code eventIds} where the user has confirmed attendance (batch). */
	java.util.Set<String> findAttendingEventIds(String userId, java.util.Collection<String> eventIds);

	int countAttendees(String eventId);

	/** Presencas dos eventos da pagina, em UMA consulta (evita N+1 na vitrine). */
	java.util.Map<String, Integer> countAttendeesByEventIds(java.util.Collection<String> eventIds);

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
