package com.socialcoisa.rede.calendar.domain;

/** As duas agendas independentes. A consolidada é a união delas na consulta, não um terceiro valor. */
public enum CalendarScope {
	SOCIAL,
	PROFESSIONAL;

	/** Um valor vindo de banco ou de configuração; recusa o que não é agenda em vez de cair num padrão. */
	public static CalendarScope parse(String raw) {
		for (CalendarScope scope : values()) {
			if (scope.name().equals(raw)) {
				return scope;
			}
		}
		throw new IllegalArgumentException("agenda desconhecida: " + raw);
	}
}
