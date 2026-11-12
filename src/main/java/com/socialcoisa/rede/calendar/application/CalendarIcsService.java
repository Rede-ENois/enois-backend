package com.socialcoisa.rede.calendar.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

import org.springframework.beans.factory.annotation.Value;
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
	/**
	 * Teto do upload. Um arquivo de agenda pessoal não é um dump, e não deve virar um. O limite do
	 * multipart é de centenas de MB por causa de vídeo; sem este teto, o import leria e analisaria o
	 * arquivo inteiro antes de recusar.
	 */
	private static final int MAX_IMPORT_EVENTS = 500;
	/** Teto de blocos por `VTIMEZONE`: um fuso não muda de regra mais que isso numa janela. */
	private static final int MAX_TIMEZONE_OBSERVANCES = 400;
	private static final String SOURCE_TYPE = "ICS";

	private final CalendarEntryRepository entries;
	private final CalendarSourceService source;
	private final int maxImportBytes;

	public CalendarIcsService(CalendarEntryRepository entries, CalendarSourceService source,
			@Value("${rede.calendar.ics-max-import-bytes:524288}") int maxImportBytes) {
		this.entries = entries;
		this.source = source;
		this.maxImportBytes = maxImportBytes;
	}

	// --- export --------------------------------------------------------------

	/**
	 * O `.ics` da agenda de quem está lendo na janela pedida. Cancelado entra com
	 * `STATUS:CANCELLED` e o `UID` que sempre foi dele.
	 *
	 * <p>Um `VTIMEZONE` por fuso que a janela realmente usa, e cada um só com as transições da
	 * janela — o export de um mês não carrega décadas de mudança de regra.
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
			vtimezone(ics, zone, from, to);
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
	 *
	 * <p>O bloco abre com a observância em vigor no começo da janela: um arquivo de janeiro precisa
	 * do deslocamento de janeiro, mesmo que a última transição tenha sido no ano anterior. E o
	 * recorte é o da janela pedida — percorrer o tzdata desde 1970 para exportar um mês era
	 * caminhada sem uso.
	 */
	private static void vtimezone(StringBuilder ics, ZoneId zone, Instant from, Instant to) {
		line(ics, "BEGIN:VTIMEZONE");
		line(ics, "TZID:" + zone.getId());
		ZoneRules rules = zone.getRules();
		// Uma borda de um dia garante que a mudança no próprio instante da janela entre.
		Instant cursor = from.minus(Duration.ofDays(1));
		Instant limit = to.plus(Duration.ofDays(1));
		// A observância que já valia na borda da janela abre o bloco. O deslocamento de entrada e de
		// saída são o mesmo: a regra começa valendo, e quem muda é a próxima transição.
		ZoneOffset current = rules.getOffset(cursor);
		observance(ics, LocalDateTime.ofInstant(cursor, current), current, current,
			rules.isDaylightSavings(cursor));
		int emitted = 1;
		for (ZoneOffsetTransition transition = rules.nextTransition(cursor);
				transition != null && !transition.getInstant().isAfter(limit)
					&& emitted < MAX_TIMEZONE_OBSERVANCES;
				transition = rules.nextTransition(transition.getInstant())) {
			observance(ics, transition.getDateTimeBefore(), transition.getOffsetBefore(),
				transition.getOffsetAfter(), daylight(transition));
			emitted++;
		}
		line(ics, "END:VTIMEZONE");
	}

	private static boolean daylight(ZoneOffsetTransition transition) {
		return transition.getOffsetAfter().getTotalSeconds() > transition.getOffsetBefore().getTotalSeconds();
	}

	private static void observance(StringBuilder ics, LocalDateTime startsAt, ZoneOffset before,
			ZoneOffset after, boolean daylight) {
		line(ics, daylight ? "BEGIN:DAYLIGHT" : "BEGIN:STANDARD");
		line(ics, "DTSTART:" + ICS_TIME.format(startsAt));
		line(ics, "TZOFFSETFROM:" + offset(before));
		line(ics, "TZOFFSETTO:" + offset(after));
		line(ics, daylight ? "END:DAYLIGHT" : "END:STANDARD");
	}

	/** Origem presente é o UID (estável entre exportações); entrada sem origem usa o id próprio. */
	private static String uidOf(CalendarEntry entry) {
		CalendarEntrySource source = entry.source();
		return source.present() ? source.type() + "-" + source.id() : "enois-" + entry.id();
	}

	/** `SEQUENCE` cresce a cada alteração: o leitor externo usa isso para aceitar a revisão nova. */
	private static long sequence(CalendarEntry entry) {
		return Math.max(0, Duration.between(entry.createdAt(), entry.updatedAt()).getSeconds());
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
	 *
	 * <p>Os dois tetos contam coisas diferentes. O de bytes protege memória e tempo de análise, e
	 * é lido de um arquivo temporário: um corpo de centenas de MB não cabe num array só para
	 * responder 400. O de eventos limita quantas linhas o import pode criar de uma vez, e também
	 * evita que `BEGIN:VEVENT` sobrepostos confundam o parser.
	 */
	public ImportResult importCalendar(String userId, InputStream content, CalendarScope scope) {
		if (content == null) {
			throw new ApiException(400, "INVALID_ICS", "Envie o arquivo iCalendar no campo file.");
		}
		String body = readLimited(content);
		if (body.isBlank()) {
			throw new ApiException(400, "INVALID_ICS", "Envie o arquivo iCalendar no campo file.");
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
				end = dateOnly ? start.plus(Duration.ofDays(1)) : start.plus(Duration.ofHours(1));
			}
			if (Duration.between(start, end).compareTo(CalendarEntry.MAX_DURATION) > 0) {
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

	private String readLimited(InputStream content) {
		try (InputStream in = content; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] chunk = new byte[8192];
			int total = 0;
			int read;
			while ((read = in.read(chunk)) != -1) {
				total += read;
				if (total > maxImportBytes) {
					throw tooLarge();
				}
				out.write(chunk, 0, read);
			}
			return out.toString(StandardCharsets.UTF_8);
		}
		catch (IOException error) {
			throw new ApiException(400, "INVALID_ICS", "Não foi possível ler o arquivo enviado.");
		}
	}

	private ApiException tooLarge() {
		return new ApiException(400, "INVALID_ICS",
			"O arquivo passa de " + (maxImportBytes / 1024) + " KB.");
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
				// Sobreposição não é um `VEVENT` a mais: é sintaxe quebrada. Recomeçar engoliria o
				// evento de fora e contaria o de dentro como se fosse só um.
				if (current != null) {
					throw new ApiException(400, "INVALID_ICS",
						"Um VEVENT começa antes do anterior terminar; o arquivo está malformado.");
				}
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

	/**
	 * Desfaz o escape do RFC 5545 numa passada só. A versão em cadeia de `replace` errava o caso
	 * mais comum de texto com barra invertida: em `\\n` (barra escapada seguida de "n") o primeiro
	 * `replace` casava a segunda barra com o "n" e produzia barra + quebra de linha, em vez do
	 * literal `\n` que o arquivo dizia. Uma passada respeita o par barra+caractere de cada vez.
	 */
	private static String unescape(String text) {
		if (text.indexOf('\\') < 0) {
			return text;
		}
		StringBuilder out = new StringBuilder(text.length());
		for (int index = 0; index < text.length(); index++) {
			char current = text.charAt(index);
			if (current != '\\' || index + 1 >= text.length()) {
				out.append(current);
				continue;
			}
			char next = text.charAt(++index);
			switch (next) {
				case 'n', 'N' -> out.append('\n');
				case ',', ';', '\\' -> out.append(next);
				default -> out.append('\\').append(next);
			}
		}
		return out.toString();
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
		CalendarWindows.validate(from, to, "exportar a agenda");
	}
}
