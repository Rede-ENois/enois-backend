package com.socialcoisa.rede.calendar.domain;

/**
 * Situação do compromisso. `CANCELLED` fica no banco em vez de virar DELETE porque o arquivo
 * iCalendar precisa publicar o cancelamento (`STATUS:CANCELLED`) com o mesmo `UID` — evento que
 * some do arquivo nunca é removido na agenda de quem assinou.
 */
public enum CalendarEntryState {
	ACTIVE,
	CANCELLED;

	public static CalendarEntryState parse(String raw) {
		for (CalendarEntryState state : values()) {
			if (state.name().equals(raw)) {
				return state;
			}
		}
		throw new IllegalArgumentException("situação de compromisso desconhecida: " + raw);
	}
}
