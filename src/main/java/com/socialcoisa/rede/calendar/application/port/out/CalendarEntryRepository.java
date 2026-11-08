package com.socialcoisa.rede.calendar.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;

/** Porta de persistência da agenda. A interface não conhece JPA. */
public interface CalendarEntryRepository {

	void save(CalendarEntry entry);

	Optional<CalendarEntry> findById(String id);

	/**
	 * Entradas do dono que tocam a janela, ativas ou não. A janela é meia-aberta: um compromisso que
	 * termina exatamente quando a janela começa não aparece, e um que começa quando ela termina
	 * também não.
	 *
	 * @param scope nulo significa as duas agendas (a visão consolidada)
	 */
	List<CalendarEntry> findInWindow(String ownerId, CalendarScope scope, Instant windowStart, Instant windowEnd);

	Optional<CalendarEntry> findBySource(String ownerId, CalendarEntrySource source);

}
