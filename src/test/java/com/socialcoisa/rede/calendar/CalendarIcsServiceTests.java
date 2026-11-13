package com.socialcoisa.rede.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.socialcoisa.rede.calendar.application.CalendarIcsService;
import com.socialcoisa.rede.calendar.application.CalendarRecurrenceService;
import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.application.port.out.CalendarParticipantRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarRecurrence;
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

	@Autowired
	private CalendarParticipantRepository participants;

	@Autowired
	private CalendarRecurrenceService recurrences;

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

	/** Fluxo que conta o que foi lido e não guarda nada, para provar que o teto para a leitura. */
	private static final class CountingStream extends InputStream {

		private final long size;
		private long read;

		CountingStream(long size) {
			this.size = size;
		}

		long consumed() {
			return read;
		}

		@Override
		public int read() {
			if (read >= size) {
				return -1;
			}
			read++;
			return '\n';
		}

		@Override
		public int read(byte[] buffer, int offset, int length) {
			if (read >= size) {
				return -1;
			}
			int chunk = (int) Math.min(length, size - read);
			Arrays.fill(buffer, offset, offset + chunk, (byte) '\n');
			read += chunk;
			return chunk;
		}
	}

	/** O import recebe fluxo: o mesmo corpo de antes, embrulhado como o multipart entrega. */
	private static InputStream stream(String body) {
		return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
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

	/**
	 * O compromisso que o leitor organiza e o que foi convidado saem no mesmo arquivo, e o
	 * cancelamento publica `STATUS:CANCELLED` também no convidado: o calendário externo de quem
	 * aceitou precisa saber que o evento acabou (D6). Um filtro de estado no cruzamento de convite
	 * esconderia esta linha, e o evento ficaria para sempre na agenda de quem assina.
	 */
	@Test
	void invitedEntriesExportTooAndTheirCancellationKeepsTheSameUid() {
		String owner = owner();
		String guest = "ics_guest_" + UUID.randomUUID();
		String id = "cal_" + UUID.randomUUID();
		save(owner, id, STARTS_AT, STARTS_AT.plus(Duration.ofHours(1)), "Festa do time");
		participants.save(com.socialcoisa.rede.calendar.domain.CalendarParticipant.invite(
			"p_" + UUID.randomUUID(), id, owner, guest, Instant.now()));

		String before = service.export(guest, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)), null);
		assertThat(before).contains("SUMMARY:Festa do time");

		CalendarEntry cancelled = entries.findById(id).orElseThrow();
		cancelled.cancel(Instant.now());
		entries.save(cancelled);

		String after = service.export(guest, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)), null);
		assertThat(after).contains("UID:enois-" + id);
		assertThat(after).contains("STATUS:CANCELLED");
	}

	@Test
	void theTimeZoneBlockCoversTheWindowAndNotTheWholeHistory() {
		String owner = owner();
		ZoneId newYork = ZoneId.of("America/New_York");
		Instant start = Instant.parse("2026-11-20T15:00:00Z");
		entries.save(CalendarEntry.create("cal_" + UUID.randomUUID(), owner, CalendarScope.SOCIAL,
			CalendarKind.PERSONAL, new CalendarEntrySource(null, null), "Reunião em Nova York", null,
			start, start.plus(Duration.ofHours(1)), newYork, false, Instant.now()));

		String exported = service.export(owner, start.minus(Duration.ofDays(2)),
			start.plus(Duration.ofDays(2)), null);

		// A janela de quatro dias não tem mudança de horário: uma observância fixa com o
		// deslocamento de novembro (-05:00), e nada de caminhar o tzdata desde 1970.
		String block = exported.substring(exported.indexOf("BEGIN:VTIMEZONE"),
			exported.indexOf("END:VTIMEZONE"));
		assertThat(block).contains("TZID:America/New_York")
			.contains("TZOFFSETTO:-0500")
			.doesNotContain("BEGIN:DAYLIGHT")
			.doesNotContain("TZOFFSETTO:-0400");
		assertThat(exported).contains("SUMMARY:Reunião em Nova York");
	}

	@Test
	void aWindowThatCrossesDaylightSavingCarriesBothOffsets() {
		String owner = owner();
		ZoneId newYork = ZoneId.of("America/New_York");
		// A América do Norte volta ao horário padrão em 1º de novembro de 2026 (06:00 UTC).
		Instant start = Instant.parse("2026-10-30T15:00:00Z");
		entries.save(CalendarEntry.create("cal_" + UUID.randomUUID(), owner, CalendarScope.SOCIAL,
			CalendarKind.PERSONAL, new CalendarEntrySource(null, null), "Antes e depois", null,
			start, start.plus(Duration.ofHours(1)), newYork, false, Instant.now()));

		String exported = service.export(owner, start, start.plus(Duration.ofDays(5)), null);

		String block = exported.substring(exported.indexOf("BEGIN:VTIMEZONE"),
			exported.indexOf("END:VTIMEZONE"));
		assertThat(block).contains("BEGIN:DAYLIGHT").contains("TZOFFSETTO:-0400")
			.contains("BEGIN:STANDARD").contains("TZOFFSETTO:-0500");
	}

	@Test
	void onlyTheZonesTheEventsUseGetABlock() {
		String owner = owner();
		save(owner, "cal_" + UUID.randomUUID(), STARTS_AT, STARTS_AT.plus(Duration.ofHours(1)), "Só São Paulo");

		String exported = service.export(owner, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)), null);

		assertThat(exported).contains("TZID:America/Sao_Paulo");
		assertThat(exported).doesNotContain("TZID:America/New_York").doesNotContain("TZID:Europe/");
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
			assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
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
		CalendarIcsService.ImportResult first = service.importCalendar(target, stream(ics), null);
		CalendarIcsService.ImportResult second = service.importCalendar(target, stream(ics), null);

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

		CalendarIcsService.ImportResult result = service.importCalendar(owner, stream(ics), null);

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
		assertThat(service.importCalendar(owner, stream(active), null).created()).isEqualTo(1);

		String cancelled = active.replace("STATUS:CONFIRMED", "STATUS:CANCELLED");
		CalendarIcsService.ImportResult result = service.importCalendar(owner, stream(cancelled), null);

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
		service.importCalendar(owner, stream(cancelled.replace("STATUS:CANCELLED", "STATUS:CONFIRMED")), null);

		CalendarIcsService.ImportResult first = service.importCalendar(owner, stream(cancelled), null);
		CalendarIcsService.ImportResult second = service.importCalendar(owner, stream(cancelled), null);

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
		CalendarIcsService.ImportResult result = service.importCalendar(target, stream(ics), null);

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

		CalendarIcsService.ImportResult result = service.importCalendar(owner, stream(ics), null);

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

		CalendarIcsService.ImportResult result = service.importCalendar(owner, stream(ics), null);

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

		CalendarIcsService.ImportResult result = service.importCalendar(owner, stream(ics), null);

		assertThat(result.received()).isEqualTo(2);
		assertThat(result.created()).isEqualTo(1);
		assertThat(result.skipped()).isEqualTo(1);
	}

	@Test
	void aBackslashInTheTextSurvivesTheRoundTrip() {
		String source = owner();
		// Barra invertida não tem escape próprio no RFC 5545 além de `\\`: este texto exportado
		// vira `C:\\pasta\\novo`, e o import tem de devolver exatamente o que entrou.
		String title = "Revisar C:\\pasta\\novo";
		save(source, "cal_" + UUID.randomUUID(), STARTS_AT, STARTS_AT.plus(Duration.ofHours(1)), title);
		String ics = service.export(source, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)), null);
		assertThat(ics).contains("SUMMARY:Revisar C:\\\\pasta\\\\novo");

		String target = owner();
		service.importCalendar(target, stream(ics), null);

		List<CalendarEntry> imported = entries.findInWindow(target, null, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)));
		assertThat(imported).hasSize(1);
		assertThat(imported.get(0).title()).isEqualTo(title);
	}

	@Test
	void escapedBackslashFollowedByLetterIsNotTurnedIntoALineBreak() {
		String owner = owner();
		String ics = """
			BEGIN:VCALENDAR\r
			BEGIN:VEVENT\r
			UID:barra-1\r
			DTSTART;TZID=America/Sao_Paulo:20261201T190000\r
			DTEND;TZID=America/Sao_Paulo:20261201T200000\r
			SUMMARY:Revisar C:\\\\pasta\\\\novo\r
			DESCRIPTION:linha um\\nlinha dois\r
			END:VEVENT\r
			END:VCALENDAR\r
			""";

		service.importCalendar(owner, stream(ics), null);

		List<CalendarEntry> imported = entries.findInWindow(owner, null, STARTS_AT.minus(Duration.ofDays(1)),
			STARTS_AT.plus(Duration.ofDays(1)));
		assertThat(imported).hasSize(1);
		assertThat(imported.get(0).title()).isEqualTo("Revisar C:\\pasta\\novo");
		// `\n` de verdade continua virando quebra de linha; só a barra escapada é literal.
		assertThat(imported.get(0).description()).isEqualTo("linha um\nlinha dois");
	}

	@Test
	void anImportCannotBecomeADump() {
		String owner = owner();
		StringBuilder huge = new StringBuilder("BEGIN:VCALENDAR\r\n");
		// UID numérico curto de propósito: com "evento-" + índice o arquivo passava de 512 KB e quem
		// respondia era o teto de bytes. Com os UIDs curtos ele fica abaixo do teto de tamanho, e o
		// que recusa é o teto de compromissos.
		for (int index = 0; index < 505; index++) {
			huge.append("BEGIN:VEVENT\r\nUID:").append(index)
				.append("\r\nDTSTART;TZID=America/Sao_Paulo:20261201T190000\r\nEND:VEVENT\r\n");
		}
		huge.append("END:VCALENDAR\r\n");
		assertThat(huge.toString().getBytes(StandardCharsets.UTF_8).length)
			.isLessThan(512 * 1024);

		assertThatThrownBy(() -> service.importCalendar(owner, stream(huge.toString()), null))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("compromissos");
	}

	@Test
	void anUploadOverTheByteCeilingIsRefusedBeforeTheWholeFileIsRead() {
		String owner = owner();
		// 20 MB de preenchimento: o teto é de 512 KB, e o arquivo nunca deve ser inteiro consumido.
		CountingStream stream = new CountingStream(20 * 1024 * 1024);

		assertThatThrownBy(() -> service.importCalendar(owner, stream, null))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("512 KB");
		assertThat(stream.consumed()).isLessThan(600 * 1024);
	}

	@Test
	void aFileJustUnderTheByteCeilingStillImports() {
		String owner = owner();
		StringBuilder body = new StringBuilder("BEGIN:VCALENDAR\r\n");
		while (body.length() < 500 * 1024) {
			body.append("X-PREENCHIMENTO:").append("a".repeat(200)).append("\r\n");
		}
		body.append("BEGIN:VEVENT\r\nUID:quase-no-teto\r\n")
			.append("DTSTART;TZID=America/Sao_Paulo:20261201T190000\r\nSUMMARY:Cabe\r\nEND:VEVENT\r\n")
			.append("END:VCALENDAR\r\n");

		assertThat(service.importCalendar(owner, stream(body.toString()), null).created()).isEqualTo(1);
		// E o teto continua sendo o de 512 KB, não o de eventos: um evento só, arquivo grande.
		assertThat(body.length()).isGreaterThan(500 * 1024);
	}

	@Test
	void anEventThatStartsBeforeThePreviousOneEndsIsRejectedAsMalformed() {
		String owner = owner();
		String nested = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:fora\r\n"
			+ "DTSTART;TZID=America/Sao_Paulo:20261201T190000\r\nBEGIN:VEVENT\r\nUID:dentro\r\n"
			+ "DTSTART;TZID=America/Sao_Paulo:20261202T190000\r\nEND:VEVENT\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";

		assertThatThrownBy(() -> service.importCalendar(owner, stream(nested), null))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("malformado");
		assertThat(entries.findInWindow(owner, null, STARTS_AT.minus(Duration.ofDays(30)),
			STARTS_AT.plus(Duration.ofDays(30)))).isEmpty();
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
