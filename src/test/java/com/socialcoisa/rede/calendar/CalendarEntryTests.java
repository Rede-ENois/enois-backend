package com.socialcoisa.rede.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarEntryState;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarScope;

/**
 * O que este teste tranca: o compromisso não nasce sem título, sem fuso reconhecido ou com fim antes
 * do início; reagendar para o mesmo horário não mexe em `updatedAt` (é o que mantém o `SEQUENCE` do
 * arquivo .ics estável); cancelado continua no banco para poder ser anunciado como cancelado; e a
 * colisão de horário é de intervalo real, não de igualdade de início.
 */
class CalendarEntryTests {

	private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

	@Test
	void createsAnEntryWithTheOrganizerTimeZone() {
		CalendarEntry entry = entry(Instant.parse("2026-11-09T22:00:00Z"), Instant.parse("2026-11-09T23:00:00Z"));

		assertThat(entry.scope()).isEqualTo(CalendarScope.SOCIAL);
		assertThat(entry.kind()).isEqualTo(CalendarKind.PERSONAL);
		assertThat(entry.zone()).isEqualTo(SAO_PAULO);
		assertThat(entry.state()).isEqualTo(CalendarEntryState.ACTIVE);
		assertThat(entry.duration()).isEqualTo(Duration.ofHours(1));
		assertThat(entry.source().present()).isFalse();
	}

	@Test
	void refusesAnIntervalThatDoesNotAdvanceOrThatLastsAWeek() {
		Instant start = Instant.parse("2026-11-09T22:00:00Z");

		assertThatThrownBy(() -> entry(start, start)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> entry(start, start.minusSeconds(60))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> entry(start, start.plus(Duration.ofDays(8))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("7");
	}

	@Test
	void refusesATimeZoneTheSystemDoesNotKnow() {
		assertThatThrownBy(() -> CalendarEntry.zoneOf("Marte/Olympus"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Marte/Olympus");
		assertThatThrownBy(() -> CalendarEntry.zoneOf(" ")).isInstanceOf(IllegalArgumentException.class);
		assertThat(CalendarEntry.zoneOf("Europe/Lisbon")).isEqualTo(ZoneId.of("Europe/Lisbon"));
	}

	@Test
	void reschedulingToTheSameInstantDoesNotDirtyTheEntry() {
		CalendarEntry entry = entry(Instant.parse("2026-11-09T22:00:00Z"), Instant.parse("2026-11-09T23:00:00Z"));
		Instant later = NOW.plus(Duration.ofHours(1));

		entry.reschedule(entry.startsAt(), entry.endsAt(), SAO_PAULO, later);

		assertThat(entry.updatedAt()).isEqualTo(NOW);
	}

	@Test
	void reschedulingMovesTheIntervalAndTheZone() {
		CalendarEntry entry = entry(Instant.parse("2026-11-09T22:00:00Z"), Instant.parse("2026-11-09T23:00:00Z"));
		Instant newStart = Instant.parse("2026-11-10T22:00:00Z");

		entry.reschedule(newStart, newStart.plus(Duration.ofHours(1)), ZoneId.of("Europe/Lisbon"),
			NOW.plus(Duration.ofHours(2)));

		assertThat(entry.startsAt()).isEqualTo(newStart);
		assertThat(entry.zone()).isEqualTo(ZoneId.of("Europe/Lisbon"));
		assertThat(entry.updatedAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
	}

	@Test
	void aCancelledEntryStaysAndRefusesFurtherEdits() {
		CalendarEntry entry = entry(Instant.parse("2026-11-09T22:00:00Z"), Instant.parse("2026-11-09T23:00:00Z"));

		entry.cancel(NOW.plus(Duration.ofMinutes(5)));

		assertThat(entry.state()).isEqualTo(CalendarEntryState.CANCELLED);
		assertThat(entry.cancelledAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
		assertThatThrownBy(() -> entry.rename("outro nome", NOW)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void reclassifyChangesOnlyWhatCameAndLeavesTheRest() {
		CalendarEntry entry = entry(Instant.parse("2026-11-09T22:00:00Z"), Instant.parse("2026-11-09T23:00:00Z"));

		// Só o escopo veio: tipo e dia inteiro continuam os que estavam.
		entry.reclassify(CalendarScope.PROFESSIONAL, null, null, NOW.plus(Duration.ofMinutes(1)));
		assertThat(entry.scope()).isEqualTo(CalendarScope.PROFESSIONAL);
		assertThat(entry.kind()).isEqualTo(CalendarKind.PERSONAL);
		assertThat(entry.allDay()).isFalse();
		assertThat(entry.updatedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));

		// Mesmo valor não é mudança: `updatedAt` fica onde estava (o `SEQUENCE` do `.ics` também).
		Instant afterScope = entry.updatedAt();
		entry.reclassify(CalendarScope.PROFESSIONAL, CalendarKind.PERSONAL, false, NOW.plus(Duration.ofHours(1)));
		assertThat(entry.updatedAt()).isEqualTo(afterScope);
	}

	@Test
	void aCancelledEntryCannotBeReclassified() {
		CalendarEntry entry = entry(Instant.parse("2026-11-09T22:00:00Z"), Instant.parse("2026-11-09T23:00:00Z"));
		entry.cancel(NOW);

		assertThatThrownBy(() -> entry.reclassify(CalendarScope.PROFESSIONAL, CalendarKind.MEETING, true, NOW))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void overlapIsHalfOpenOnBothSides() {
		CalendarEntry entry = entry(Instant.parse("2026-11-09T22:00:00Z"), Instant.parse("2026-11-09T23:00:00Z"));
		Instant start = entry.startsAt();
		Instant end = entry.endsAt();

		// Encostar no fim ou no início não colide: 23h às 23h é a mesma faixa contígua.
		assertThat(entry.overlaps(start.minusSeconds(3600), start)).isFalse();
		assertThat(entry.overlaps(end, end.plusSeconds(3600))).isFalse();
		assertThat(entry.overlaps(start, start.plusSeconds(60))).isTrue();
		assertThat(entry.overlaps(start.minusSeconds(60), end.plusSeconds(60))).isTrue();
	}

	@Test
	void sourceNeedsBothHalvesOrNone() {
		assertThat(new CalendarEntrySource(null, null).present()).isFalse();
		assertThatThrownBy(() -> new CalendarEntrySource("MENTORING", null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CalendarEntrySource(null, "ms_1")).isInstanceOf(IllegalArgumentException.class);
	}

	private static CalendarEntry entry(Instant startsAt, Instant endsAt) {
		return CalendarEntry.create("cal_1", "user_1", CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), "Aula de violão", null, startsAt, endsAt, SAO_PAULO, false, NOW);
	}

}
