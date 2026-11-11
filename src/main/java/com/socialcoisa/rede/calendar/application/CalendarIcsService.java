package com.socialcoisa.rede.calendar.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Exportação e importação de iCalendar da agenda de quem está lendo (fase 8, decisões D6/D3).
 *
 * <p><b>Export:</b> o arquivo cobre o que a pessoa organiza e o que foi convidada. Cada compromisso
 * sai com `UID` estável (a origem, quando existe; `enois-` + id quando não), `SEQUENCE` crescente
 * com `updatedAt` e `STATUS:CANCELLED` no cancelamento — evento que some do arquivo nunca é
 * removido na agenda de quem assinou, então o cancelamento precisa ser publicado com o mesmo UID.
 * Os horários saem com `TZID` explícito no fuso de cada compromisso, nunca achatados em UTC, e cada
 * fuso usado ganha um `VTIMEZONE` derivado das transições reais (D6).
 *
 * <p><b>Import:</b> cada `VEVENT` vira uma entrada com origem `ICS` + `UID` do arquivo, então
 * repetir o mesmo arquivo não cria segunda linha (D3). Isso é deliberadamente separado de um
 * compromisso criado na agenda: ele não tem origem, e importar não mescla com o que já existe.
 */
@Service
public class CalendarIcsService {

	private static final DateTimeFormatter ICS_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
	private static final DateTimeFormatter ICS_TIME_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
	private static final DateTimeFormatter ICS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
	/** RFC 5545 §3.1: cada linha tem no máximo 75 octets, sem contar o CRLF. */
	private static final int MAX_LINE = 75;
	/** Teto do upload: um arquivo de agenda pessoal não é um dump, e não deve virar um. */
	private static final int MAX_IMPORT_BYTES = 512 * 1024;
	private static final int MAX_IMPORT_EVENTS = 500;
	private static final String SOURCE_TYPE = "ICS";

	private final CalendarEntryRepository entries;
	private final CalendarSourceService source;
	private final Clock clock;

	public CalendarIcsService(CalendarEntryRepository entries, CalendarSourceService source, Clock clock) {
		this.entries = entries;
		this.source = source;
		this.clock = clock;
	}

	// --- export --------------------------------------------------------------

	/**
	 * O `.ics` da agenda de quem está lendo na janela pedida. Cancelado entra com
	 * `STATUS:CANCELLED` e o `UID` que sempre foi dele.
	 */
	public String export(String userId, Instant from, Instant to, CalendarScope scope) {
		validateWindow(from, to);
		List<CalendarEntry> agenda = new ArrayList<>(entries.findInWindow(userId, scope, from, to));
		agenda.addAll(entries.findInvitedInWindow(userId, scope, from, to));
		agenda.sort((left, right) -> left.startsAt().compareTo(right.startsAt()));

		Map<String, ZoneId> zones = new LinkedHashMap<>();
		for (CalendarEntry entry : agenda) {
			zones.putIfAbsent(entry.zone().getId(), entry.zone());
		}

		StringBuilder ics = new StringBuilder();
		line(ics, "BEGIN:VCALENDAR");
		line(ics, "VERSION:2.0");
		line(ics, "PRODID:-//Rede Enois//Agenda//PT-BR");
		line(ics, "CALSCALE:GREGORIAN");
		line(ics, "METHOD:PUBLISH");
		for (ZoneId zone : zones.values()) {
			vtimezone(ics, zone);
		}
		for (CalendarEntry entry : agenda) {
			vevent(ics, entry);
		}
		line(ics, "END:VCALENDAR");
		return ics.toString();
	}

	private void vevent(StringBuilder ics, CalendarEntry entry) {
		String zoneId = entry.zone().getId();
		line(ics, "BEGIN:VEVENT");
		line(ics, "UID:" + uidOf(entry));
		line(ics, "SEQUENCE:" + sequence(entry));
		line(ics, "DTSTAMP:" + stamp(entry.updatedAt(), ZoneOffset.UTC));
		if (entry.allDay()) {
			line(ics, "DTSTART;VALUE=DATE:" + ICS_DATE.format(ZonedDateTime.ofInstant(entry.startsAt(), entry.zone()).toLocalDate()));
			line(ics, "DTEND;VALUE=DATE:" + ICS_DATE.format(ZonedDateTime.ofInstant(entry.endsAt(), entry.zone()).toLocalDate()));
		}
		else {
			line(ics, "DTSTART;TZID=" + zoneId + ":" + stamp(entry.startsAt(), entry.zone()));
			line(ics, "DTEND;TZID=" + zoneId + ":" + stamp(entry.endsAt(), entry.zone()));
		}
		line(ics, "SUMMARY:" + escape(entry.title()));
		if (entry.description() != null && !entry.description().isBlank()) {
			line(ics, "DESCRIPTION:" + escape(entry.description()));
		}
		line(ics, entry.active() ? "STATUS:CONFIRMED" : "STATUS:CANCELLED");
		line(ics, "END:VEVENT");
	}

	/**
	 * `VTIMEZONE` a partir das transições reais do fuso. Um `STANDARD` fixo com o deslocamento de
	 * hoje erraria metade do ano em regiões com horário de verão; a lista de observâncias cobre
	 * exatamente cada mudança que o tzdata conhece no intervalo exportado.
	 */
	private void vtimezone(StringBuilder ics, ZoneId zone) {
		line(ics, "BEGIN:VTIMEZONE");
		line(ics, "TZID:" + zone.getId());
		ZoneRules rules = zone.getRules();
		Instant cursor = Instant.parse("1970-01-01T00:00:00Z");
		Instant limit = Instant.now().plus(java.time.Duration.ofDays(3650));
		int emitted = 0;
		while (cursor.isBefore(limit) && emitted < 400) {
			ZoneOffsetTransition transition = rules.nextTransition(cursor);
			if (transition == null || transition.getInstant().isAfter(limit)) {
				break;
			}
			boolean daylight = transition.getOffsetAfter().getTotalSeconds()
				> transition.getOffsetBefore().getTotalSeconds();
			line(ics, daylight ? "BEGIN:DAYLIGHT" : "BEGIN:STANDARD");
			line(ics, "DTSTART:" + ICS_TIME.format(transition.getDateTimeBefore()));
			line(ics, "TZOFFSETFROM:" + offset(transition.getOffsetBefore()));
			line(ics, "TZOFFSETTO:" + offset(transition.getOffsetAfter()));
			line(ics, daylight ? "END:DAYLIGHT" : "END:STANDARD");
			cursor = transition.getInstant();
			emitted++;
		}
		if (emitted == 0) {
			// Fuso sem transição (UTC, por exemplo): uma observância fixa é o arquivo inteiro.
			line(ics, "BEGIN:STANDARD");
			line(ics, "DTSTART:19700101T000000");
			line(ics, "TZOFFSETFROM:" + offset(rules.getOffset(Instant.EPOCH)));
			line(ics, "TZOFFSETTO:" + offset(rules.getOffset(Instant.EPOCH)));
			line(ics, "END:STANDARD");
		}
		line(ics, "END:VTIMEZONE");
	}

	/** Origem presente é o UID (estável entre exportações); entrada sem origem usa o id próprio. */
	private static String uidOf(CalendarEntry entry) {
		CalendarEntrySource source = entry.source();
		return source.present() ? source.type() + "-" + source.id() : "enois-" + entry.id();
	}

	/** `SEQUENCE` cresce a cada alteração: o leitor externo usa isso para aceitar a revisão nova. */
	private static long sequence(CalendarEntry entry) {
		return Math.max(0, java.time.Duration.between(entry.createdAt(), entry.updatedAt()).getSeconds());
	}

	private static String stamp(Instant instant, ZoneId zone) {
		return ICS_TIME.format(ZonedDateTime.ofInstant(instant, zone));
	}

	private static String offset(ZoneOffset offset) {
		int seconds = offset.getTotalSeconds();
		int abs = Math.abs(seconds);
		return String.format("%s%02d%02d", seconds < 0 ? "-" : "+", abs / 3600, (abs % 3600) / 60);
	}

	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n")
			.replace("\n", "\\n");
	}

	/**
	 * Uma linha de propriedade, quebrada em pedaços de até 75 octets com continuação por espaço
	 * (RFC 5545 §3.1). A quebra conta octets de UTF-8, mas nunca parte um caractere ao meio.
	 */
	private static void line(StringBuilder ics, String text) {
		int used = 0;
		boolean first = true;
		for (int index = 0; index < text.length();) {
			int codePoint = text.codePointAt(index);
			int octets = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
			int limit = first ? MAX_LINE : MAX_LINE - 1;
			if (used + octets > limit) {
				ics.append("\r\n ");
				first = false;
				used = 0;
				continue;
			}
			ics.appendCodePoint(codePoint);
			used += octets;
			index += Character.charCount(codePoint);
		}
		ics.append("\r\n");
	}

	// --- import --------------------------------------------------------------

	/**
	 * Importa o arquivo na agenda de quem está lendo. `VEVENT` sem UID ou sem horário não tem
	 * identidade suficiente e é contado em `skipped`, sem derrubar o arquivo inteiro: um arquivo
	 * real traz `VTIMEZONE`, `VALARM` e eventos alheios ao que este app representa.
	 */
	public ImportResult importCalendar(String userId, String body, CalendarScope scope) {
		if (body == null || body.isBlank()) {
			throw new ApiException(400, "INVALID_ICS", "Envie o arquivo iCalendar no corpo.");
		}
		if (body.getBytes(StandardCharsets.UTF_8).length > MAX_IMPORT_BYTES) {
			throw new ApiException(400, "INVALID_ICS", "O arquivo passa de 512 KB.");
		}
		List<Vevent> events = parse(body);
		if (events.size() > MAX_IMPORT_EVENTS) {
			throw new ApiException(400, "INVALID_ICS", "O arquivo passa de " + MAX_IMPORT_EVENTS + " compromissos.");
		}
		CalendarScope target = scope == null ? CalendarScope.SOCIAL : scope;
		int created = 0;
		int moved = 0;
		int cancelled = 0;
		int skipped = 0;
		for (Vevent event : events) {
			Instant start = instantOf(event.start(), event.startZone());
			Instant end = instantOf(event.end(), event.endZone() != null ? event.endZone() : event.startZone());
			if (event.uid() == null || event.uid().isBlank() || start == null) {
				skipped++;
				continue;
			}
			// Cancelamento é estado, não ausência (D10/D26): o `STATUS:CANCELLED` do arquivo cancela
			// a entrada local com o mesmo UID e nunca cria uma nova. Sem entrada local não há o que
			// cancelar, e criar um compromisso já cancelado só poluiria a agenda.
			if (event.cancelled()) {
				if (source.cancel(userId, SOURCE_TYPE, event.uid()).isPresent()) {
					cancelled++;
				}
				else {
					skipped++;
				}
				continue;
			}
			boolean dateOnly = isDateOnly(event.start());
			if (end == null || !end.isAfter(start)) {
				end = dateOnly ? start.plus(java.time.Duration.ofDays(1)) : start.plus(java.time.Duration.ofHours(1));
			}
			if (java.time.Duration.between(start, end).compareTo(CalendarEntry.MAX_DURATION) > 0) {
				skipped++;
				continue;
			}
			ZoneId zone = zoneOf(event.startZone());
			String title = event.summary() == null || event.summary().isBlank() ? "Compromisso importado"
				: event.summary();
			CalendarSourceService.UpsertResult result = source.upsert(new CalendarSourceService.UpsertInput(
				userId, SOURCE_TYPE, event.uid(), target, CalendarKind.PERSONAL, title, event.description(),
				start, end, zone.getId(), dateOnly));
			if (result.created()) {
				created++;
			}
			else if (result.moved()) {
				moved++;
			}
		}
		return new ImportResult(events.size(), created, moved, cancelled, skipped);
	}

	private static ZoneId zoneOf(String raw) {
		if (raw == null || raw.isBlank()) {
			return ZoneId.of("America/Sao_Paulo");
		}
		try {
			return CalendarEntry.zoneOf(raw);
		}
		catch (IllegalArgumentException unknown) {
			// Um TZID que este servidor não conhece não invalida o arquivo: o evento entra no fuso
			// padrão em vez de ser descartado.
			return ZoneId.of("America/Sao_Paulo");
		}
	}

	/**
	 * `20261120T190000` com `TZID` vira o instante naquele fuso; com `Z` é UTC; só data vira o
	 * começo do dia no fuso do evento. Texto que não casa devolve nulo, e quem chamou decide.
	 */
	private static Instant instantOf(String raw, String zoneId) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String value = raw.trim();
		try {
			if (value.endsWith("Z")) {
				return LocalDateTime.parse(value, ICS_TIME_UTC).toInstant(ZoneOffset.UTC);
			}
			if (value.length() == 8) {
				return LocalDate.parse(value, ICS_DATE).atStartOfDay(zoneOf(zoneId)).toInstant();
			}
			return LocalDateTime.parse(value, ICS_TIME).atZone(zoneOf(zoneId)).toInstant();
		}
		catch (DateTimeParseException malformed) {
			return null;
		}
	}

	private static boolean isDateOnly(String raw) {
		return raw != null && raw.trim().length() == 8;
	}

	private static List<Vevent> parse(String body) {
		List<Vevent> events = new ArrayList<>();
		List<String> unfolded = unfold(body);
		Map<String, String> current = null;
		for (String line : unfolded) {
			if (line.equalsIgnoreCase("BEGIN:VEVENT")) {
				current = new LinkedHashMap<>();
				continue;
			}
			if (line.equalsIgnoreCase("END:VEVENT")) {
				if (current != null) {
					events.add(new Vevent(current.get("UID"), current.get("SUMMARY"), current.get("DESCRIPTION"),
						current.get("DTSTART"), current.get("DTSTART.TZID"), current.get("DTEND"),
						current.get("DTEND.TZID"), "CANCELLED".equalsIgnoreCase(current.get("STATUS"))));
				}
				current = null;
				continue;
			}
			if (current == null || !line.contains(":")) {
				continue;
			}
			int at = line.indexOf(':');
			String left = line.substring(0, at);
			String value = unescape(line.substring(at + 1));
			String name = left.split(";")[0].toUpperCase();
			if (!List.of("UID", "SUMMARY", "DESCRIPTION", "DTSTART", "DTEND", "STATUS").contains(name)) {
				continue;
			}
			current.put(name, value);
			if (name.equals("DTSTART") || name.equals("DTEND")) {
				int tzid = left.indexOf("TZID=");
				if (tzid >= 0) {
					String zone = left.substring(tzid + 5);
					int semicolon = zone.indexOf(';');
					current.put(name + ".TZID", semicolon >= 0 ? zone.substring(0, semicolon) : zone);
				}
			}
		}
		return events;
	}

	private static List<String> unfold(String body) {
		List<String> lines = new ArrayList<>();
		for (String raw : body.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1)) {
			if ((raw.startsWith(" ") || raw.startsWith("\t")) && !lines.isEmpty()) {
				lines.set(lines.size() - 1, lines.get(lines.size() - 1) + raw.substring(1));
			}
			else {
				lines.add(raw);
			}
		}
		return lines;
	}

	private static String unescape(String text) {
		return text.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";")
			.replace("\\\\", "\\");
	}

	private record Vevent(String uid, String summary, String description, String start, String startZone,
			String end, String endZone, boolean cancelled) {
	}

	/**
	 * `received` é quantos `VEVENT` o arquivo trazia; `created` quantos nasceram agora; `moved`
	 * quantos já existiam e mudaram de horário; `cancelled` quantos cancelaram uma entrada local
	 * pelo mesmo UID; `skipped` quantos não tinham o mínimo para virar compromisso (ou eram
	 * cancelamentos de algo que não existe aqui).
	 */
	public record ImportResult(int received, int created, int moved, int cancelled, int skipped) {
	}

	private static void validateWindow(Instant from, Instant to) {
		if (from == null || to == null) {
			throw new ApiException(400, "INVALID_WINDOW", "Informe from e to para exportar a agenda.");
		}
		if (!to.isAfter(from)) {
			throw new ApiException(400, "INVALID_WINDOW", "to precisa ser depois de from.");
		}
		if (java.time.Duration.between(from, to).compareTo(java.time.Duration.ofDays(366)) > 0) {
			throw new ApiException(400, "INVALID_WINDOW", "A exportação cobre no máximo um ano.");
		}
	}
}