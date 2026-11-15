package com.socialcoisa.rede.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.socialcoisa.rede.calendar.application.CalendarIcsService;
import com.socialcoisa.rede.calendar.application.CalendarRecurrenceService;
import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarRecurrence;

/**
 * O limite da série (`UNTIL` do arquivo importado) sobrevive ao banco e ao worker (V322).
 *
 * <p>O que estes testes trancam: o `UNTIL` flutuante e o só-data resolvem no fuso do evento (não num
 * fuso fixo do servidor); o limite fica gravado nas linhas da série; e o worker de extensão não
 * reabre uma série que o limite encerrou — sem a coluna, a ponta ficava aberta e a série voltava a
 * crescer para além do que o arquivo dizia a cada ciclo.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class CalendarSeriesLimitTests {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
	private static final Instant STARTS_AT = Instant.parse("2026-12-01T22:00:00Z");

	@Autowired
	private CalendarIcsService ics;

	@Autowired
	private CalendarRecurrenceService recurrences;

	@Autowired
	private CalendarEntryRepository entries;

	private static String owner() {
		return "ics_lim_" + UUID.randomUUID();
	}

	private static InputStream stream(String body) {
		return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
	}

	private static String weeklyUntil(String uid, String untilLine, String tzid) {
		return """
			BEGIN:VCALENDAR\r
			BEGIN:VEVENT\r
			UID:%s\r
			DTSTART;TZID=%s:20261201T190000\r
			DTEND;TZID=%s:20261201T200000\r
			SUMMARY:Série com limite\r
			RRULE:FREQ=WEEKLY;INTERVAL=1;%s\r
			END:VEVENT\r
			END:VCALENDAR\r
			""".formatted(uid, tzid, tzid, untilLine);
	}

	/** `UNTIL` flutuante é horário de parede do evento: 15/12 19h em São Paulo, não em outro fuso. */
	@Test
	void aFloatingUntilResolvesInTheEventZone() {
		String owner = owner();
		ics.importCalendar(owner, stream(weeklyUntil("limite-flutuante", "UNTIL=20261215T190000",
			"America/Sao_Paulo")), null);

		List<CalendarEntry> imported = entries.findInWindow(owner, null, STARTS_AT,
			STARTS_AT.plus(Duration.ofDays(90)));
		assertThat(imported).hasSize(3);
		assertThat(imported.stream().map(CalendarEntry::startsAt)).containsExactly(
			Instant.parse("2026-12-01T22:00:00Z"), Instant.parse("2026-12-08T22:00:00Z"),
			Instant.parse("2026-12-15T22:00:00Z"));
		Instant limit = ZonedDateTime.of(2026, 12, 15, 19, 0, 0, 0, SAO_PAULO).toInstant();
		for (CalendarEntry entry : imported) {
			assertThat(entry.seriesUntil()).isEqualTo(limit);
		}
		// Extensão de novo não cria nada e não deixa ponta aberta para o worker reabrir.
		CalendarEntry last = imported.get(imported.size() - 1);
		assertThat(recurrences.extend(last, Instant.now())).isZero();
		assertThat(entries.findById(last.id()).orElseThrow().tail()).isFalse();
	}

	/** O só-data `UNTIL` é meia-noite de parede do evento: inclui o que começa até lá (RFC 5545). */
	@Test
	void aDateOnlyUntilResolvesInTheEventZone() {
		String owner = owner();
		// Meia-noite do dia 9 em São Paulo inclui a ocorrência de 08/12 19h e para antes da de 15/12.
		ics.importCalendar(owner, stream(weeklyUntil("limite-so-data", "UNTIL=20261209",
			"America/Sao_Paulo")), null);

		List<CalendarEntry> imported = entries.findInWindow(owner, null, STARTS_AT,
			STARTS_AT.plus(Duration.ofDays(90)));
		assertThat(imported).hasSize(2);
		Instant limit = ZonedDateTime.of(2026, 12, 9, 0, 0, 0, 0, SAO_PAULO).toInstant();
		assertThat(imported.get(0).seriesUntil()).isEqualTo(limit);
	}

	/** Um `UNTIL` em UTC é absoluto: 19h de São Paulo vira 21h de parede, e a série para nele. */
	@Test
	void aUtcUntilStopsTheSeriesAtTheInstant() {
		String owner = owner();
		ics.importCalendar(owner, stream(weeklyUntil("limite-utc", "UNTIL=20261208T220000Z",
			"America/Sao_Paulo")), null);

		List<CalendarEntry> imported = entries.findInWindow(owner, null, STARTS_AT,
			STARTS_AT.plus(Duration.ofDays(90)));
		assertThat(imported).hasSize(2);
		assertThat(imported.get(0).seriesUntil()).isEqualTo(Instant.parse("2026-12-08T22:00:00Z"));
	}

	/** O worker não reabre a série limitada: `findSeriesTails` não volta a listá-la. */
	@Test
	void theWorkerNeverReopensALimitedSeries() {
		String owner = owner();
		ics.importCalendar(owner, stream(weeklyUntil("limite-worker", "UNTIL=20261215T190000",
			"America/Sao_Paulo")), null);

		List<CalendarEntry> imported = entries.findInWindow(owner, null, STARTS_AT,
			STARTS_AT.plus(Duration.ofDays(90)));
		assertThat(imported).allSatisfy(entry -> assertThat(entry.tail()).isFalse());
		assertThat(entries.findSeriesTails())
			.noneMatch(entry -> owner.equals(entry.ownerId()));
	}

	/** Encerrar a série apaga o limite junto — a entrada vira compromisso único de verdade. */
	@Test
	void stoppingTheSeriesAlsoClearsTheLimit() {
		String owner = owner();
		ics.importCalendar(owner, stream(weeklyUntil("limite-stop", "UNTIL=20261215T190000",
			"America/Sao_Paulo")), null);

		List<CalendarEntry> imported = entries.findInWindow(owner, null, STARTS_AT,
			STARTS_AT.plus(Duration.ofDays(90)));
		CalendarEntry founder = imported.get(0);

		recurrences.stop(owner, founder.id());

		CalendarEntry after = entries.findById(founder.id()).orElseThrow();
		assertThat(after.seriesId()).isNull();
		assertThat(after.seriesUntil()).isNull();
	}
}
