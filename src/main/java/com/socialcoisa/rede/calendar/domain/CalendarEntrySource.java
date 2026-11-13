package com.socialcoisa.rede.calendar.domain;

/**
 * De onde o compromisso veio. Entrada criada no próprio calendário não tem origem, e nesse caso os
 * dois campos são nulos; a tabela garante que não existe um sem o outro.
 *
 * @param type o módulo dono (`EVENT`, `MENTORING`, `JOB_INTERVIEW`, `ICS`, ...)
 * @param id   o identificador da linha naquele módulo
 */
public record CalendarEntrySource(String type, String id) {

	public CalendarEntrySource {
		boolean hasType = type != null && !type.isBlank();
		boolean hasId = id != null && !id.isBlank();
		if (hasType != hasId) {
			throw new IllegalArgumentException("origem precisa do tipo e do id, ou de nenhum dos dois");
		}
	}

	public boolean present() {
		return type != null;
	}
}
