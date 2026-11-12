package com.socialcoisa.rede.calendar.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.calendar.domain.CalendarParticipant;

/** Porta de persistência dos convites. A interface não conhece JPA. */
public interface CalendarParticipantRepository {

	void save(CalendarParticipant participant);

	void delete(CalendarParticipant participant);

	Optional<CalendarParticipant> findById(String id);

	Optional<CalendarParticipant> findByEntryAndUser(String entryId, String userId);

	/** Convites de uma pessoa para um conjunto de compromissos, em uma consulta só. */
	List<CalendarParticipant> findByUserAndEntryIds(String userId, List<String> entryIds);

	/** Todos os participantes de um compromisso, na ordem em que foram convidados. */
	List<CalendarParticipant> findByEntry(String entryId);

	/** Quantos convites o compromisso tem, sem carregar as linhas: teto do convite. */
	long countByEntry(String entryId);

}
