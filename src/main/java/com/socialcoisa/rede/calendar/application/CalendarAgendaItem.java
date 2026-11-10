package com.socialcoisa.rede.calendar.application;

import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarParticipantState;

/**
 * O que a agenda de uma pessoa devolve: o compromisso e o lugar dela nele.
 *
 * @param entry         o compromisso
 * @param owner         verdadeiro quando quem está lendo organizou
 * @param participation a resposta dela quando é convidada; nulo quando é a dona
 */
public record CalendarAgendaItem(CalendarEntry entry, boolean owner, CalendarParticipantState participation) {

	public static CalendarAgendaItem owned(CalendarEntry entry) {
		return new CalendarAgendaItem(entry, true, null);
	}

	public static CalendarAgendaItem invited(CalendarEntry entry, CalendarParticipantState participation) {
		return new CalendarAgendaItem(entry, false, participation);
	}

}
