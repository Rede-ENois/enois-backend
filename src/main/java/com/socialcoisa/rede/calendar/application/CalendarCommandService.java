package com.socialcoisa.rede.calendar.application;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Escrita da agenda do próprio usuário. Toda validação do domínio (intervalo, duração, fuso, título)
 * chega aqui como {@link IllegalArgumentException} e sai como 400 com código, porque a mensagem da
 * exceção é escrita para quem está usando o app.
 */
@Service
public class CalendarCommandService {

	private final CalendarEntryRepository entries;
	private final CalendarQueryService query;
	private final Clock clock;

	public CalendarCommandService(CalendarEntryRepository entries, CalendarQueryService query, Clock clock) {
		this.entries = entries;
		this.query = query;
		this.clock = clock;
	}

	public CalendarEntry create(String ownerId, String scope, String kind, String title, String description,
			String startsAt, String endsAt, String timeZoneId, boolean allDay) {
		String id = "cal_" + UUID.randomUUID().toString().replace("-", "");
		CalendarEntry entry = translate(() -> CalendarEntry.create(id, ownerId, scope(scope), kind(kind),
			new CalendarEntrySource(null, null), title, blankToNull(description), instant(startsAt, "startsAt"),
			instant(endsAt, "endsAt"), CalendarEntry.zoneOf(timeZoneId), allDay, clock.instant()));
		entries.save(entry);
		return entry;
	}

	/**
	 * Edição parcial: campo ausente não muda. Descrição vazia limpa o campo, porque no JSON uma
	 * string em branco é a única forma de dizer "tirar o texto" que não se confunde com "não mexi".
	 * Reagendar exige início e fim juntos: meio intervalo novo não tem significado.
	 */
	public CalendarEntry update(String ownerId, String entryId, String title, String description, String startsAt,
			String endsAt, String timeZoneId) {
		CalendarEntry entry = query.get(ownerId, entryId);
		Instant start = nullableInstant(startsAt, "startsAt");
		Instant end = nullableInstant(endsAt, "endsAt");
		if ((start == null) != (end == null)) {
			throw new ApiException(400, "INVALID_CALENDAR_ENTRY", "Informe startsAt e endsAt juntos.");
		}
		if (timeZoneId != null && start == null) {
			throw new ApiException(400, "INVALID_CALENDAR_ENTRY",
				"Para trocar o fuso, informe também startsAt e endsAt.");
		}
		translate(() -> {
			Instant now = clock.instant();
			if (title != null) {
				entry.rename(title, now);
			}
			if (description != null) {
				entry.describe(blankToNull(description), now);
			}
			if (start != null) {
				entry.reschedule(start, end, CalendarEntry.zoneOf(timeZoneId), now);
			}
			return entry;
		});
		entries.save(entry);
		return entry;
	}

	/** Cancelar não apaga a linha: o `.ics` precisa do mesmo `UID` com `STATUS:CANCELLED`. */
	public CalendarEntry cancel(String ownerId, String entryId) {
		CalendarEntry entry = query.get(ownerId, entryId);
		if (!entry.active()) {
			// Repetir o cancelamento é o que o botão pode fazer depois de um timeout; não é erro.
			return entry;
		}
		translate(() -> {
			entry.cancel(clock.instant());
			return entry;
		});
		entries.save(entry);
		return entry;
	}

	private static CalendarScope scope(String raw) {
		if (raw == null || raw.isBlank()) {
			return CalendarScope.SOCIAL;
		}
		try {
			return CalendarScope.parse(raw.trim());
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(400, "INVALID_SCOPE", "Agenda invalida: use SOCIAL ou PROFESSIONAL.");
		}
	}

	private static CalendarKind kind(String raw) {
		if (raw == null || raw.isBlank()) {
			return CalendarKind.PERSONAL;
		}
		try {
			return CalendarKind.parse(raw.trim());
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(400, "INVALID_KIND", "Tipo de compromisso invalido.");
		}
	}

	private static Instant instant(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			throw new ApiException(400, "INVALID_CALENDAR_ENTRY", "Informe " + field + " em ISO-8601.");
		}
		return parse(raw, field);
	}

	private static Instant nullableInstant(String raw, String field) {
		return raw == null ? null : parse(raw, field);
	}

	private static Instant parse(String raw, String field) {
		try {
			return Instant.parse(raw.trim());
		}
		catch (DateTimeParseException exception) {
			throw new ApiException(400, "INVALID_CALENDAR_ENTRY",
				field + " precisa ser um instante ISO-8601 em UTC.");
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static <T> T translate(Supplier<T> action) {
		try {
			return action.get();
		}
		catch (IllegalStateException exception) {
			throw new ApiException(409, "CALENDAR_ENTRY_CANCELLED", exception.getMessage());
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(400, "INVALID_CALENDAR_ENTRY", exception.getMessage());
		}
	}
}
