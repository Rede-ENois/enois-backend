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

	/**
	 * Apaga a entrada da origem. É o oposto de {@link #save}: existe para o caso em que o fato que
	 * gerou o compromisso deixou de valer (a pessoa saiu do evento), e não para "cancelar" — o
	 * cancelamento é estado, não remoção.
	 */
	void deleteBySource(String ownerId, CalendarEntrySource source);

	/**
	 * Os compromissos com convite dessa pessoa ainda sem resposta, do convite mais antigo ao mais
	 * novo. Cancelado ou já respondido não volta: a consulta cruza convite e compromisso no banco.
	 */
	List<CalendarEntry> findPendingInvitations(String userId);

}
