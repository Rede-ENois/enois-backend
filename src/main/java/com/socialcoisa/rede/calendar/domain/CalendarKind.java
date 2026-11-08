package com.socialcoisa.rede.calendar.domain;

/**
 * O que o compromisso é para a tela. Não carrega regra: validação de entrevista, de mentoria e de
 * sala continua no módulo dono do compromisso. Aqui só existe para escolher ícone, texto e cor.
 */
public enum CalendarKind {
	PERSONAL,
	EVENT,
	MENTORING,
	INTERVIEW,
	MEETING,
	CALL,
	WORKSHOP;

	public static CalendarKind parse(String raw) {
		for (CalendarKind kind : values()) {
			if (kind.name().equals(raw)) {
				return kind;
			}
		}
		throw new IllegalArgumentException("tipo de compromisso desconhecido: " + raw);
	}
}
