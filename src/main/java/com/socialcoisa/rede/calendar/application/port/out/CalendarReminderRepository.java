package com.socialcoisa.rede.calendar.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.calendar.domain.CalendarReminder;

/** Porta de persistência dos lembretes. A interface não conhece JPA. */
public interface CalendarReminderRepository {

	void save(CalendarReminder reminder);

	List<CalendarReminder> findByEntry(String entryId);

	/** O lembrete de uma pessoa para um compromisso numa antecedência (a chave única da tabela). */
	Optional<CalendarReminder> findByEntryAndUserAndMinutes(String entryId, String userId, int minutesBefore);

	/** Os que já dispararam o horário, ainda não foram enviados e têm tentativa sobrando. */
	List<CalendarReminder> findDue(Instant now, int attemptsBelow, int limit);

	/** Os que ainda não saíram de um conjunto de compromissos (cancelamento, reagendamento). */
	List<CalendarReminder> findPendingByEntries(java.util.Collection<String> entryIds);

}