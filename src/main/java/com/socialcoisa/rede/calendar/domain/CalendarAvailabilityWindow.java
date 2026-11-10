package com.socialcoisa.rede.calendar.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Janela semanal de disponibilidade da agenda (fase 6, decisão D24).
 *
 * <p>É uma regra da pessoa, não de um compromisso: "toda terça, das 9h às 12h, no horário de São
 * Paulo". O `MentorAvailability` do perfil de mentor continua sendo outra coisa — lá o horário é o
 * de um slot de mentoria, aqui é quando a agenda admite compromisso.
 *
 * <p>Os horários são de parede, no fuso declarado, e não instantes: a regra é semanal e precisa
 * sobreviver à mudança de horário de verão. A janela é meio-aberta (início incluído, fim excluído):
 * uma janela que acaba às 12h e outra que começa às 12h não se sobrepõem.
 *
 * <p>Não existe janela que atravessa a meia-noite: quem precisa das 22h às 2h declara duas janelas,
 * uma em cada dia. Guardar uma janela assim com um horário só perderia o dia do fim.
 */
public record CalendarAvailabilityWindow(String id, String userId, int dayOfWeek, LocalTime startsAt,
		LocalTime endsAt, ZoneId zone, Instant createdAt) {

	public static final int MIN_DAY = 1; // ISO-8601: 1 = segunda, 7 = domingo
	public static final int MAX_DAY = 7;

	public CalendarAvailabilityWindow {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("id da janela é obrigatório");
		}
		if (userId == null || userId.isBlank()) {
			throw new IllegalArgumentException("pessoa da janela é obrigatória");
		}
		if (dayOfWeek < MIN_DAY || dayOfWeek > MAX_DAY) {
			throw new IllegalArgumentException("dia da semana precisa estar entre 1 (segunda) e 7 (domingo)");
		}
		if (startsAt == null || endsAt == null) {
			throw new IllegalArgumentException("início e fim da janela são obrigatórios");
		}
		if (!startsAt.isBefore(endsAt)) {
			throw new IllegalArgumentException("o fim da janela precisa ser depois do começo");
		}
		if (zone == null) {
			throw new IllegalArgumentException("fuso da janela é obrigatório");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("criação da janela é obrigatória");
		}
	}

	public static CalendarAvailabilityWindow create(String id, String userId, int dayOfWeek, LocalTime startsAt,
			LocalTime endsAt, ZoneId zone, Instant now) {
		return new CalendarAvailabilityWindow(id, userId, dayOfWeek, startsAt, endsAt, zone, now);
	}

	/** Meio-aberto: encostar no fim da outra não é sobrepor. Duas janelas no mesmo dia se cruzam. */
	public boolean overlaps(CalendarAvailabilityWindow other) {
		return dayOfWeek == other.dayOfWeek && startsAt.isBefore(other.endsAt) && endsAt.isAfter(other.startsAt);
	}

}