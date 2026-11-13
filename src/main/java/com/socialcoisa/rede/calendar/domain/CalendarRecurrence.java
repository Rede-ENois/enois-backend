package com.socialcoisa.rede.calendar.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * A cadência de uma série de compromissos. `NONE` é o compromisso único; as outras formam uma série
 * materializada em linhas irmãs (`series_id` igual, horário próprio).
 *
 * <p>O passo é calculado em relógio de parede no fuso da série, não em instantes: uma reunião "toda
 * terça às 19h" tem de continuar às 19h depois da mudança de horário, e somar 7 dias em `Instant` a
 * deixaria às 18h ou às 20h no fim de semana de transição. Por isso a série avança de ocorrência em
 * ocorrência por {@link ZonedDateTime}, com o fuso que a entrada já guarda.
 *
 * <p>A mensal conta os meses a partir da âncora da série (o mesmo que {@code events.EventRecurrence}
 * faz): o compromisso do dia 31 cai no último dia dos meses curtos e volta ao 31 depois deles, sem
 * derivar para o dia 3 de cada mês.
 */
public enum CalendarRecurrence {

	NONE,
	WEEKLY,
	BIWEEKLY,
	MONTHLY;

	/**
	 * Até quando a série mantém ocorrências materializadas à frente de agora. Janela em dias, não em
	 * quantidade: a agenda quer "três meses à frente" em qualquer cadência, e contar ocorrências faria
	 * a semanal materializar 12 semanas e a mensal 12 meses.
	 */
	public static final int HORIZON_DAYS = 90;

	/**
	 * Teto de ocorrências criadas numa passada da extensão. É proteção contra cadência futura mais
	 * frequente que a janela comporta; hoje a semanal gera ~13 e a mensal ~3.
	 */
	public static final int MAX_OCCURRENCES_PER_RUN = 200;

	/**
	 * Começo da ocorrência seguinte à que começa em {@code current}, no fuso da série, com o dia da
	 * âncora da própria ocorrência. A duração de cada ocorrência é a da anterior na cadeia; o fim
	 * segue o começo.
	 */
	public Instant nextStart(Instant current, java.time.ZoneId zone) {
		ZonedDateTime wall = current.atZone(Objects.requireNonNull(zone, "fuso"));
		return nextStart(current, zone, wall.getDayOfMonth());
	}

	/**
	 * Começo da ocorrência seguinte, com o dia da âncora da série para a mensal: o compromisso do dia
	 * 31 cai no último dia dos meses curtos e volta ao 31 depois deles, em vez de derivar para o 28
	 * para sempre. A semanal e a quinzenal ignoram a âncora — o passo delas é inteiro, em semanas.
	 */
	public Instant nextStart(Instant current, java.time.ZoneId zone, int anchorDayOfMonth) {
		if (this == NONE) {
			throw new IllegalStateException("compromisso único não tem próxima ocorrência");
		}
		ZonedDateTime wall = current.atZone(Objects.requireNonNull(zone, "fuso"));
		ZonedDateTime next = switch (this) {
			case WEEKLY -> wall.plusWeeks(1);
			case BIWEEKLY -> wall.plusWeeks(2);
			case MONTHLY -> {
				YearMonth nextMonth = YearMonth.from(wall).plusMonths(1);
				int capped = Math.min(Math.max(anchorDayOfMonth, 1), nextMonth.lengthOfMonth());
				yield nextMonth.atDay(capped).atTime(wall.toLocalTime()).atZone(zone);
			}
			case NONE -> throw new IllegalStateException("compromisso único não tem próxima ocorrência");
		};
		return next.toInstant();
	}

	/** Um valor vindo de banco, de JSON ou de configuração recusa o que não é cadência. */
	public static CalendarRecurrence parse(String raw) {
		for (CalendarRecurrence value : values()) {
			if (value.name().equals(raw)) {
				return value;
			}
		}
		throw new IllegalArgumentException("cadência desconhecida: " + raw);
	}
}
