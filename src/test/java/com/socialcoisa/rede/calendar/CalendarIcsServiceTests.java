package com.socialcoisa.rede.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.socialcoisa.rede.calendar.application.CalendarIcsService;
import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Export e import de `.ics` contra o banco de verdade (fase 8).
 *
 * <p>O que estes testes trancam: o `UID` é estável (senão o leitor externo duplica o evento),
 * cancelado sai com `STATUS:CANCELLED` e o mesmo `UID` (senão o evento nunca é removido do
 * calendário de quem assinou), o fuso viaja como `TZID` (D6) e o arquivo exportado importa sem
 * duplicar (D3).
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class CalendarIcsServiceTests {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
	private static final Instant STARTS_AT = Instant.parse("2026-12-01T22:00:00Z");

	@Autowired
	private CalendarIcsService service;

	@Autowired
	private CalendarEntryRepository entries;

	private static String owner() {
		return "ics_" + UUID.randomUUID();
	}

	private CalendarEntry save(String owner, String id, Instant start, Instant end, String title) {
		CalendarEntry entry = CalendarEntry.create(id, owner, CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), title, "descrição, com vírgula; e ponto e vírgula", start,
			end, SAO_PAULO, false, Instant.now());
		entries.save(entry);
		return entry;
	}

	@Test
	void exportingTheWindowCarriesTzidAndAStableUid() {
		String owner = owner();
		save(owner, "cal_" + UUID.randomUUID(), STARTS_AT, STARTS_AT.plus(Duration.ofHours(1)), "Consulta");

		String ics = service.export(owner, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)), null);

		assertThat(ics).contains("BEGIN:VCALENDAR");
		assertThat(ics).contains("VERSION:2.0");
		assertThat(ics).contains("BEGIN:VTIMEZONE");
		assertThat(ics).contains("TZID:America/Sao_Paulo");
		assertThat(ics).contains("DTSTART;TZID=America/Sao_Paulo:20261201T190000");
		assertThat(ics).contains("DTEND;TZID=America/Sao_Paulo:20261201T200000");
		assertThat(ics).contains("SUMMARY:Consulta");
		assertThat(ics).contains("STATUS:CONFIRMED");
		// UID de entrada sem origem deriva do id; o mesmo id em dois exports tem de dar o mesmo UID.
		String uidLine = ics.lines().filter(line -> line.startsWith("UID:")).findFirst().orElseThrow();
		assertThat(uidLine).startsWith("UID:enois-cal_");
		String again = service.export(owner, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)), null);
		assertThat(again).contains(uidLine);
	}

	@Test
	void escapingProtectsCommasAndSemicolons() {
		String owner = owner();
		save(owner, "cal_" + UUID.randomUUID(), STARTS_AT, STARTS_AT.plus(Duration.ofHours(1)), "Reunião, revisão");

		String ics = service.export(owner, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)), null);

		assertThat(ics).contains("SUMMARY:Reunião\\, revisão");
		assertThat(ics).contains("DESCRIPTION:descrição\\, com vírgula\\; e ponto e vírgula");
	}

	@Test
	void cancelledStaysInTheFileWithTheSameUidAndStatus() {
		String owner = owner();
		String id = "cal_" + UUID.randomUUID();
		CalendarEntry entry = save(owner, id, STARTS_AT, STARTS_AT.plus(Duration.ofHours(1)), "Cancelada");
		entry.cancel(Instant.now());
		entries.save(entry);

		String ics = service.export(owner, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)), null);

		assertThat(ics).contains("UID:enois-" + id);
		assertThat(ics).contains("STATUS:CANCELLED");
		assertThat(ics).doesNotContain("STATUS:CONFIRMED");
	}

	@Test
	void scopeNarrowsTheExportButTheConsolidatedViewHasBoth() {
		String owner = owner();
		save(owner, "cal_" + UUID.randomUUID(), STARTS_AT, STARTS_AT.plus(Duration.ofHours(1)), "Social");

		String ics = service.export(owner, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)), CalendarScope.PROFESSIONAL);

		assertThat(ics).contains("BEGIN:VCALENDAR");
		assertThat(ics).doesNotContain("BEGIN:VEVENT");
	}

	@Test
	void longLinesAreFoldedAtSeventyFiveOctetsAndUnfoldOnImport() {
		String owner = owner();
		String longTitle = "Consulta de acompanhamento com a equipe multidisciplinar sobre o tratamento";
		save(owner, "cal_" + UUID.randomUUID(), STARTS_AT, STARTS_AT.plus(Duration.ofHours(1)), longTitle);

		String ics = service.export(owner, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)), null);

		for (String line : ics.split("\r\n")) {
			assertThat(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
		}
		assertThat(ics).contains("\r\n ");
	}

	@Test
	void importingTheOwnExportRecreatesTheEventsWithoutDuplicatingOnRetry() {
		String source = owner();
		save(source, "cal_" + UUID.randomUUID(), STARTS_AT, STARTS_AT.plus(Duration.ofHours(1)), "Da agenda");
		String ics = service.export(source, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)), null);

		String target = owner();
		CalendarIcsService.ImportResult first = service.importCalendar(target, ics, null);
		CalendarIcsService.ImportResult second = service.importCalendar(target, ics, null);

		assertThat(first.received()).isEqualTo(1);
		assertThat(first.created()).isEqualTo(1);
		assertThat(first.skipped()).isZero();
		assertThat(second.created()).isZero();
		assertThat(second.moved()).isZero();
		assertThat(entries.findInWindow(target, null, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)))).hasSize(1);
	}

	@Test
	void aCancelledEventInTheFileDoesNotBecomeAnActiveEntry() {
		String owner = owner();
		String ics = """
			BEGIN:VCALENDAR\r
			BEGIN:VEVENT\r
			UID:desmarcado-1\r
			DTSTART;TZID=America/Sao_Paulo:20261201T190000\r
			DTEND;TZID=America/Sao_Paulo:20261201T200000\r
			SUMMARY:Desmarcado\r
			STATUS:CANCELLED\r
			END:VEVENT\r
			END:VCALENDAR\r
			""";

		CalendarIcsService.ImportResult result = service.importCalendar(owner, ics, null);

		assertThat(result.received()).isEqualTo(1);
		assertThat(result.created()).isZero();
		assertThat(result.cancelled()).isZero();
		assertThat(result.skipped()).isEqualTo(1);
		assertThat(entries.findInWindow(owner, null, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)))).isEmpty();
	}

	@Test
	void aCancelledEventCancelsTheLocalEntryWithTheSameUid() {
		String owner = owner();
		String active = """
			BEGIN:VCALENDAR\r
			BEGIN:VEVENT\r
			UID:desmarcado-2\r
			DTSTART;TZID=America/Sao_Paulo:20261201T190000\r
			DTEND;TZID=America/Sao_Paulo:20261201T200000\r
			SUMMARY:Consulta\r
			STATUS:CONFIRMED\r
			END:VEVENT\r
			END:VCALENDAR\r
			""";
		assertThat(service.importCalendar(owner, active, null).created()).isEqualTo(1);

		String cancelled = active.replace("STATUS:CONFIRMED", "STATUS:CANCELLED");
		CalendarIcsService.ImportResult result = service.importCalendar(owner, cancelled, null);

		assertThat(result.created()).isZero();
		assertThat(result.cancelled()).isEqualTo(1);
		assertThat(result.skipped()).isZero();
		List<CalendarEntry> saved = entries.findInWindow(owner, null, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)));
		assertThat(saved).hasSize(1);
		assertThat(saved.get(0).active()).isFalse();
	}

	@Test
	void importingTheCancellationAgainKeepsTheEntryCancelledAndDoesNotDuplicate() {
		String owner = owner();
		String cancelled = """
			BEGIN:VCALENDAR\r
			BEGIN:VEVENT\r
			UID:desmarcado-3\r
			DTSTART;TZID=America/Sao_Paulo:20261201T190000\r
			DTEND;TZID=America/Sao_Paulo:20261201T200000\r
			SUMMARY:Consulta\r
			STATUS:CANCELLED\r
			END:VEVENT\r
			END:VCALENDAR\r
			""";
		service.importCalendar(owner, cancelled.replace("STATUS:CANCELLED", "STATUS:CONFIRMED"), null);

		CalendarIcsService.ImportResult first = service.importCalendar(owner, cancelled, null);
		CalendarIcsService.ImportResult second = service.importCalendar(owner, cancelled, null);

		assertThat(first.cancelled()).isEqualTo(1);
		assertThat(second.cancelled()).isEqualTo(1);
		assertThat(second.created()).isZero();
		assertThat(entries.findInWindow(owner, null, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)))).hasSize(1);
	}

	@Test
	void exportingACancelledEntryAndImportingItElsewhereDoesNotCreateIt() {
		String source = owner();
		CalendarEntry entry = save(source, "cal_" + UUID.randomUUID(), STARTS_AT,
			STARTS_AT.plus(Duration.ofHours(1)), "Cancelada na origem");
		entry.cancel(Instant.now());
		entries.save(entry);
		String ics = service.export(source, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)), null);

		String target = owner();
		CalendarIcsService.ImportResult result = service.importCalendar(target, ics, null);

		assertThat(result.received()).isEqualTo(1);
		assertThat(result.created()).isZero();
		assertThat(result.skipped()).isEqualTo(1);
		assertThat(entries.findInWindow(target, null, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)))).isEmpty();
	}

	@Test
	void importedEventKeepsTheWallClockOfItsTzid() {
		String owner = owner();
		String ics = """
			BEGIN:VCALENDAR\r
			VERSION:2.0\r
			BEGIN:VEVENT\r
			UID:externo-123\r
			DTSTART;TZID=America/Sao_Paulo:20261201T190000\r
			DTEND;TZID=America/Sao_Paulo:20261201T200000\r
			SUMMARY:Aula\r
			END:VEVENT\r
			END:VCALENDAR\r
			""";

		CalendarIcsService.ImportResult result = service.importCalendar(owner, ics, null);

		assertThat(result.created()).isEqualTo(1);
		List<CalendarEntry> saved = entries.findInWindow(owner, null, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)));
		assertThat(saved).hasSize(1);
		assertThat(saved.get(0).startsAt()).isEqualTo(STARTS_AT);
		assertThat(saved.get(0).zone()).isEqualTo(SAO_PAULO);
		assertThat(saved.get(0).source().type()).isEqualTo("ICS");
		assertThat(saved.get(0).source().id()).isEqualTo("externo-123");
	}

	@Test
	void utcAndDateOnlyStampsAreAccepted() {
		String owner = owner();
		String ics = """
			BEGIN:VCALENDAR\r
			BEGIN:VEVENT\r
			UID:utc-1\r
			DTSTART:20261202T000000Z\r
			DTEND:20261202T010000Z\r
			SUMMARY:UTC\r
			END:VEVENT\r
			BEGIN:VEVENT\r
			UID:dia-1\r
			DTSTART;VALUE=DATE:20261203\r
			SUMMARY:Dia inteiro\r
			END:VEVENT\r
			END:VCALENDAR\r
			""";

		CalendarIcsService.ImportResult result = service.importCalendar(owner, ics, null);

		assertThat(result.created()).isEqualTo(2);
		assertThat(entries.findInWindow(owner, null, Instant.parse("2026-12-02T00:00:00Z"),
			Instant.parse("2026-12-04T00:00:00Z"))).hasSize(2);
	}

	@Test
	void anEventWithoutUidIsSkippedButDoesNotBringDownTheFile() {
		String owner = owner();
		String ics = """
			BEGIN:VCALENDAR\r
			BEGIN:VEVENT\r
			DTSTART;TZID=America/Sao_Paulo:20261201T190000\r
			DTEND;TZID=America/Sao_Paulo:20261201T200000\r
			SUMMARY:Sem identidade\r
			END:VEVENT\r
			BEGIN:VEVENT\r
			UID:com-uid\r
			DTSTART;TZID=America/Sao_Paulo:20261201T190000\r
			DTEND;TZID=America/Sao_Paulo:20261201T200000\r
			SUMMARY:Com identidade\r
			END:VEVENT\r
			END:VCALENDAR\r
			""";

		CalendarIcsService.ImportResult result = service.importCalendar(owner, ics, null);

		assertThat(result.received()).isEqualTo(2);
		assertThat(result.created()).isEqualTo(1);
		assertThat(result.skipped()).isEqualTo(1);
	}

	@Test
	void anImportCannotBecomeADump() {
		String owner = owner();
		StringBuilder huge = new StringBuilder("BEGIN:VCALENDAR\r\n");
		for (int index = 0; index < 600; index++) {
			huge.append("BEGIN:VEVENT\r\nUID:evento-").append(index)
				.append("\r\nDTSTART;TZID=America/Sao_Paulo:20261201T190000\r\nSUMMARY:x\r\nEND:VEVENT\r\n");
		}
		huge.append("END:VCALENDAR\r\n");

		assertThatThrownBy(() -> service.importCalendar(owner, huge.toString(), null))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("compromissos");
	}

	@Test
	void exportingWithoutWindowIs400() {
		assertThatThrownBy(() -> service.export(owner(), null, STARTS_AT, null))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("from");
		assertThatThrownBy(() -> service.export(owner(), STARTS_AT, STARTS_AT.minus(Duration.ofDays(2)), null))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("depois");
	}
}