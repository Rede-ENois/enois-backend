package com.socialcoisa.rede.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
 * O `RRULE` do arquivo importado vira série materializada (D43), em vez de ser descartado.
 *
 * <p>O que estes testes trancam: importar um arquivo semanal cria a série inteira da janela e não
 * uma entrada só; `EXDATE` deixa o buraco que a origem pulou; `COUNT` conta a fundadora; e cadência
 * que o app não representa (diária, `BYDAY`) continua entrando como ocorrência única — melhor uma
 * ocorrência certa do que uma série inventada.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class CalendarIcsRecurrenceTests {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
	private static final Instant STARTS_AT = Instant.parse("2026-12-01T22:00:00Z");

	@Autowired
	private CalendarIcsService service;

	@Autowired
	private CalendarEntryRepository entries;

	@Autowired
	private CalendarRecurrenceService recurrences;

	private static String owner() {
		return "ics_rec_" + UUID.randomUUID();
	}

	/** O import recebe fluxo: o mesmo corpo, embrulhado como o multipart entrega. */
	private static InputStream stream(String body) {
		return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
	}


	/** Arquivo semanal com RRULE: a repetição vira série de verdade, não uma entrada só. */
	@Test
	void aWeeklyRruleInTheFileMaterializesTheSeries() {
		String owner = owner();
		String ics = """
			BEGIN:VCALENDAR\r
			BEGIN:VEVENT\r
			UID:semanal-1\r
			DTSTART;TZID=America/Sao_Paulo:20261201T190000\r
			DTEND;TZID=America/Sao_Paulo:20261201T200000\r
			SUMMARY:Aula semanal\r
			RRULE:FREQ=WEEKLY;INTERVAL=1\r
			END:VEVENT\r
			END:VCALENDAR\r
			""";

		CalendarIcsService.ImportResult result = service.importCalendar(owner, stream(ics), null);

		assertThat(result.created()).isEqualTo(1);
		Instant founderStart = Instant.parse("2026-12-01T22:00:00Z");
		List<CalendarEntry> imported = entries.findInWindow(owner, null, founderStart,
			STARTS_AT.plus(Duration.ofDays(90)));
		String seriesId = imported.get(0).seriesId();
		assertThat(seriesId).isNotNull();
		// Todas as irmãs compartilham a série e se afastam de 7 dias da fundadora...
		for (CalendarEntry sibling : imported) {
			assertThat(sibling.seriesId()).isEqualTo(seriesId);
			assertThat(sibling.recurrence()).isEqualTo(CalendarRecurrence.WEEKLY);
			long days = Duration.between(founderStart, sibling.startsAt()).toDays();
			assertThat(days % 7).isZero();
		}
		// ...só a mais distante é a ponta, e todas ficam dentro do horizonte de 90 dias.
		assertThat(imported.stream().filter(CalendarEntry::tail).count()).isEqualTo(1);
		CalendarEntry tail = imported.stream().filter(CalendarEntry::tail).findFirst().orElseThrow();
		assertThat(tail.startsAt()).isAfter(Instant.now());
		assertThat(tail.startsAt()).isBeforeOrEqualTo(Instant.now().plus(Duration.ofDays(90)));
	}

	@Test
	void anExdateInTheFileLeavesTheHoleTheOriginSkipped() {
		String owner = owner();
		String ics = """
			BEGIN:VCALENDAR\r
			BEGIN:VEVENT\r
			UID:com-exdate\r
			DTSTART;TZID=America/Sao_Paulo:20261201T190000\r
			DTEND;TZID=America/Sao_Paulo:20261201T200000\r
			SUMMARY:Série com falha\r
			RRULE:FREQ=WEEKLY;INTERVAL=1\r
			EXDATE;TZID=America/Sao_Paulo:20261215T190000\r
			END:VEVENT\r
			END:VCALENDAR\r
			""";

		service.importCalendar(owner, stream(ics), null);

		List<CalendarEntry> imported = entries.findInWindow(owner, null, STARTS_AT,
			STARTS_AT.plus(Duration.ofDays(90)));
		assertThat(imported.stream().map(CalendarEntry::startsAt))
			.doesNotContain(Instant.parse("2026-12-15T22:00:00Z"))
			.contains(Instant.parse("2026-12-08T22:00:00Z"), Instant.parse("2026-12-22T22:00:00Z"));
	}

	/** Cadência que o app não representa: a ocorrência fica única, sem acharcar série errada. */
	@Test
	void aDailyOrBydayRruleStaysSingle() {
		String owner = owner();
		String daily = """
			BEGIN:VCALENDAR\r
			BEGIN:VEVENT\r
			UID:diario-1\r
			DTSTART;TZID=America/Sao_Paulo:20261201T190000\r
			SUMMARY:Diário\r
			RRULE:FREQ=DAILY\r
			END:VEVENT\r
			END:VCALENDAR\r
			""";
		String byday = daily
			.replace("UID:diario-1", "UID:por-dia-1").replace("Diário", "Por dia da semana")
			.replace("FREQ=DAILY", "FREQ=WEEKLY;BYDAY=TU,TH");

		assertThat(service.importCalendar(owner, stream(daily), null).created()).isEqualTo(1);
		assertThat(service.importCalendar(owner, stream(byday), null).created()).isEqualTo(1);

		List<CalendarEntry> imported = entries.findInWindow(owner, null, STARTS_AT,
			STARTS_AT.plus(Duration.ofDays(90)));
		assertThat(imported).hasSize(2);
		for (CalendarEntry entry : imported) {
			assertThat(entry.seriesId()).isNull();
			assertThat(entry.recurrence()).isNull();
			assertThat(entry.tail()).isFalse();
		}
	}

	@Test
	void aCountInTheFileCapsTheSeriesIncludingTheFounder() {
		String owner = owner();
		String ics = """
			BEGIN:VCALENDAR\r
			BEGIN:VEVENT\r
			UID:contado-1\r
			DTSTART;TZID=America/Sao_Paulo:20261201T190000\r
			SUMMARY:Três vezes\r
			RRULE:FREQ=WEEKLY;COUNT=3\r
			END:VEVENT\r
			END:VCALENDAR\r
			""";

		service.importCalendar(owner, stream(ics), null);

		List<CalendarEntry> imported = entries.findInWindow(owner, null, STARTS_AT,
			STARTS_AT.plus(Duration.ofDays(90)));
		// COUNT conta a fundadora: 3 no total, não 3 à frente dela.
		assertThat(imported).hasSize(3);
		assertThat(imported.stream().map(CalendarEntry::startsAt)).containsExactly(
			Instant.parse("2026-12-01T22:00:00Z"), Instant.parse("2026-12-08T22:00:00Z"),
			Instant.parse("2026-12-15T22:00:00Z"));
	}

	/** Importar o mesmo arquivo de novo não deve nem duplicar nem recriar a série. */
	@Test
	void importingTheWeeklyFileAgainDoesNotDuplicateTheSeries() {
		String owner = owner();
		String ics = """
			BEGIN:VCALENDAR\r
			BEGIN:VEVENT\r
			UID:semanal-2\r
			DTSTART;TZID=America/Sao_Paulo:20261201T190000\r
			SUMMARY:Aula\r
			RRULE:FREQ=WEEKLY;INTERVAL=1\r
			END:VEVENT\r
			END:VCALENDAR\r
			""";
		service.importCalendar(owner, stream(ics), null);
		int first = entries.findInWindow(owner, null, STARTS_AT, STARTS_AT.plus(Duration.ofDays(90))).size();

		CalendarIcsService.ImportResult again = service.importCalendar(owner, stream(ics), null);

		assertThat(again.created()).isZero();
		int second = entries.findInWindow(owner, null, STARTS_AT, STARTS_AT.plus(Duration.ofDays(90))).size();
		assertThat(second).isEqualTo(first);
	}

	/** Encerrar a série importada funciona como a de agenda: futuro cancelado, passado fica. */
	@Test
	void stoppingAnImportedSeriesCancelsTheFutureAndKeepsThePast() {
		String owner = owner();
		String ics = """
			BEGIN:VCALENDAR\r
			BEGIN:VEVENT\r
			UID:encerravel\r
			DTSTART;TZID=America/Sao_Paulo:20261201T190000\r
			SUMMARY:Vai encerrar\r
			RRULE:FREQ=WEEKLY;INTERVAL=1\r
			END:VEVENT\r
			END:VCALENDAR\r
			""";
		service.importCalendar(owner, stream(ics), null);
		List<CalendarEntry> imported = entries.findInWindow(owner, null, STARTS_AT,
			STARTS_AT.plus(Duration.ofDays(90)));
		assertThat(imported.size()).isGreaterThan(1);

		recurrences.stop(owner, imported.get(0).id());

		List<CalendarEntry> after = entries.findInWindow(owner, null, STARTS_AT,
			STARTS_AT.plus(Duration.ofDays(90)));
		Instant now = Instant.now();
		// A entrada encerrada sai da série; as irmãs continuam nela, mas o futuro é cancelado e
		// nenhuma sobra como ponta, então o worker não volta a estender.
		assertThat(after).noneMatch(CalendarEntry::tail);
		assertThat(after).allSatisfy(entry -> {
			if (!entry.active()) {
				assertThat(entry.startsAt()).isAfter(now);
			}
		});
		assertThat(after.stream().filter(CalendarEntry::active)).isNotEmpty();
		assertThat(after).anySatisfy(entry -> {
			assertThat(entry.seriesId()).isNull();
			assertThat(entry.recurrence()).isNull();
		});
	}
}
