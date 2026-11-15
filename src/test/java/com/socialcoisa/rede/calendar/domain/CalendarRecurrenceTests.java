package com.socialcoisa.rede.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

/**
 * O passo da cadência é a regra central da série (D42): sem ele a extensão cria horário errado ou
 * perde a hora no fim de semana de transição. O que este teste tranca:
 *
 * <p>a semanal e a quinzenal avançam em semanas inteiras de relógio de parede — "toda terça às 19h"
 * continua às 19h depois da mudança de horário de verão, e não às 18h ou 20h que somar 7 dias em
 * `Instant` daria; a mensal preserva o dia da âncora, caindo no último dia do mês curto e voltando
 * ao dia cheio depois dele, em vez de derivar para o dia da última ocorrência; `NONE` não tem
 * próxima ocorrência e o valor desconhecido vindo de banco recusa, não vira cadência silenciosa.
 */
class CalendarRecurrenceTests {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

	@Test
	void weeklyStepKeepsTheWallClockAcrossTheClockChange() {
		// Lisboa muda de hora em 29/03/2026; a ocorrência de 24/03 às 19h tem de gerar 31/03 às 19h.
		ZoneId lisbon = ZoneId.of("Europe/Lisbon");
		Instant before = ZonedDateTime.of(2026, 3, 24, 19, 0, 0, 0, lisbon).toInstant();

		Instant next = CalendarRecurrence.WEEKLY.nextStart(before, lisbon);

		assertThat(next).isEqualTo(ZonedDateTime.of(2026, 3, 31, 19, 0, 0, 0, lisbon).toInstant());
	}

	@Test
	void biweeklyStepJumpsTwoWeeks() {
		Instant start = ZonedDateTime.of(2026, 11, 10, 19, 0, 0, 0, SAO_PAULO).toInstant();

		Instant next = CalendarRecurrence.BIWEEKLY.nextStart(start, SAO_PAULO);

		assertThat(next).isEqualTo(ZonedDateTime.of(2026, 11, 24, 19, 0, 0, 0, SAO_PAULO).toInstant());
	}

	@Test
	void monthlyStepCapsAtTheShortMonthAndReturnsToTheAnchorDay() {
		Instant january31 = ZonedDateTime.of(2026, 1, 31, 19, 0, 0, 0, SAO_PAULO).toInstant();

		Instant february = CalendarRecurrence.MONTHLY.nextStart(january31, SAO_PAULO, 31);
		assertThat(february).isEqualTo(ZonedDateTime.of(2026, 2, 28, 19, 0, 0, 0, SAO_PAULO).toInstant());

		Instant march = CalendarRecurrence.MONTHLY.nextStart(february, SAO_PAULO, 31);
		assertThat(march).isEqualTo(ZonedDateTime.of(2026, 3, 31, 19, 0, 0, 0, SAO_PAULO).toInstant());
	}

	@Test
	void monthlyStepWithoutAnchorUsesTheDayOfTheCurrentOccurrence() {
		Instant january15 = ZonedDateTime.of(2026, 1, 15, 9, 30, 0, 0, SAO_PAULO).toInstant();

		assertThat(CalendarRecurrence.MONTHLY.nextStart(january15, SAO_PAULO))
			.isEqualTo(ZonedDateTime.of(2026, 2, 15, 9, 30, 0, 0, SAO_PAULO).toInstant());
	}

	@Test
	void monthlyStepFromTheCappedDayStaysCappedUntilTheAnchorReturns() {
		// Sem âncora, o 28 de fevereiro propagaria o 28 para sempre; a série real usa a âncora, e o
		// teste a tranca: 28 (fim de fevereiro) com âncora 31 cai no 31 de março.
		Instant february28 = ZonedDateTime.of(2026, 2, 28, 19, 0, 0, 0, SAO_PAULO).toInstant();

		assertThat(CalendarRecurrence.MONTHLY.nextStart(february28, SAO_PAULO, 31))
			.isEqualTo(ZonedDateTime.of(2026, 3, 31, 19, 0, 0, 0, SAO_PAULO).toInstant());
	}

	@Test
	void uniqueEntryHasNoNextOccurrence() {
		Instant any = ZonedDateTime.of(2026, 11, 10, 19, 0, 0, 0, SAO_PAULO).toInstant();

		assertThatThrownBy(() -> CalendarRecurrence.NONE.nextStart(any, SAO_PAULO))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> CalendarRecurrence.NONE.nextStart(any, SAO_PAULO, 31))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void unknownValueFromStorageIsRejected() {
		assertThatThrownBy(() -> CalendarRecurrence.parse("DAILY")).isInstanceOf(IllegalArgumentException.class);
		assertThat(CalendarRecurrence.parse("WEEKLY")).isEqualTo(CalendarRecurrence.WEEKLY);
	}
}
