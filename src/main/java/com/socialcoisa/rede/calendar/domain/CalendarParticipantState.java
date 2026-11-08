package com.socialcoisa.rede.calendar.domain;

/**
 * Em que pé está o convite de uma pessoa para um compromisso alheio. O organizador não tem linha
 * aqui: ele é dono da entrada.
 *
 * <p>`TENTATIVE` existe porque "talvez" é resposta de verdade numa agenda social. Guardar como
 * "aceito" faria o organizador contar presença que não existe.
 */
public enum CalendarParticipantState {
	INVITED,
	ACCEPTED,
	DECLINED,
	TENTATIVE;

	public static CalendarParticipantState parse(String raw) {
		for (CalendarParticipantState state : values()) {
			if (state.name().equals(raw)) {
				return state;
			}
		}
		throw new IllegalArgumentException("resposta de convite desconhecida: " + raw);
	}

	/** Só quem respondeu conta como resposta dada; `INVITED` é convite pendente. */
	public boolean answered() {
		return this != INVITED;
	}
}
