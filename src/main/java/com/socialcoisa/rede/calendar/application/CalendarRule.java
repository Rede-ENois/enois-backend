package com.socialcoisa.rede.calendar.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

import com.socialcoisa.rede.calendar.domain.CalendarRecurrence;

/**
 * O subconjunto de `RRULE` do iCalendar que este app representa: cadência, intervalo, contagem e
 * limite de data.
 *
 * <p>Regra fora daqui devolve nulo — a ocorrência importada fica única em vez de entrar numa série
 * que não é o que o arquivo dizia. Diária, semanal por dia da semana (`BYDAY`) e qualquer intervalo
 * fora de 1 ou 2 semanas são exemplos do que fica de fora: não há cadência correspondente no
 * domínio, e materializar só o passo base criaria ocorrências que o arquivo não tem.
 */
record CalendarRule(CalendarRecurrence recurrence, Instant until, Integer count) {

	private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Sao_Paulo");
	private static final DateTimeFormatter UNTIL_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
	private static final DateTimeFormatter UNTIL_LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
	private static final DateTimeFormatter UNTIL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

	static CalendarRule parse(String rrule) {
		if (rrule == null || rrule.isBlank()) {
			return null;
		}
		Map<String, String> parts = new HashMap<>();
		for (String piece : rrule.split(";")) {
			int at = piece.indexOf('=');
			if (at > 0) {
				parts.put(piece.substring(0, at).toUpperCase(), piece.substring(at + 1).trim());
			}
		}
		if (parts.containsKey("BYDAY") || parts.containsKey("BYMONTHDAY") || parts.containsKey("BYMONTH")) {
			return null;
		}
		String frequency = parts.getOrDefault("FREQ", "");
		String interval = parts.getOrDefault("INTERVAL", "1");
		CalendarRecurrence recurrence = switch (frequency.toUpperCase()) {
			case "WEEKLY" -> "1".equals(interval) ? CalendarRecurrence.WEEKLY
				: "2".equals(interval) ? CalendarRecurrence.BIWEEKLY : null;
			case "MONTHLY" -> "1".equals(interval) ? CalendarRecurrence.MONTHLY : null;
			default -> null;
		};
		if (recurrence == null) {
			return null;
		}
		Integer count = null;
		if (parts.get("COUNT") != null) {
			try {
				count = Integer.parseInt(parts.get("COUNT"));
			}
			catch (NumberFormatException invalid) {
				return null;
			}
			if (count < 1) {
				return null;
			}
		}
		Instant until = null;
		if (parts.get("UNTIL") != null) {
			until = parseUntil(parts.get("UNTIL"));
			if (until == null) {
				return null;
			}
		}
		return new CalendarRule(recurrence, until, count);
	}

	/** `UNTIL` em UTC, no fuso local ou só data; texto fora do formato recusa a regra inteira. */
	private static Instant parseUntil(String raw) {
		String value = raw.trim();
		try {
			if (value.endsWith("Z")) {
				return LocalDateTime.parse(value, UNTIL_UTC).toInstant(ZoneOffset.UTC);
			}
			if (value.length() == 8) {
				return LocalDate.parse(value, UNTIL_DATE).atStartOfDay(DEFAULT_ZONE).toInstant();
			}
			return LocalDateTime.parse(value, UNTIL_LOCAL).atZone(DEFAULT_ZONE).toInstant();
		}
		catch (DateTimeParseException malformed) {
			return null;
		}
	}
}
