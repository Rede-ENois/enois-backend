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
	private final CalendarReminderService reminders;

	public CalendarCommandService(CalendarEntryRepository entries, CalendarQueryService query, Clock clock,
			CalendarReminderService reminders) {
		this.entries = entries;
		this.query = query;
		this.clock = clock;
		this.reminders = reminders;
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
	 *
	 * <p>Escopo, tipo e "dia inteiro" também são editáveis: o formulário da tela mostra os três, e
	 * descartá-los em silêncio fazia a pessoa salvar uma mudança que não acontecia.
	 */
	public CalendarEntry update(String ownerId, String entryId, String scope, String kind, String title,
			String description, String startsAt, String endsAt, String timeZoneId, Boolean allDay) {
		CalendarEntry entry = query.get(ownerId, entryId);
		requireOwnedByTheCalendar(entry, "editar");
		Instant start = nullableInstant(startsAt, "startsAt");
		Instant end = nullableInstant(endsAt, "endsAt");
		if ((start == null) != (end == null)) {
			throw new ApiException(400, "INVALID_CALENDAR_ENTRY", "Informe startsAt e endsAt juntos.");
		}
		if (timeZoneId != null && start == null) {
			throw new ApiException(400, "INVALID_CALENDAR_ENTRY",
				"Para trocar o fuso, informe também startsAt e endsAt.");
		}
		CalendarScope newScope = scope == null ? null : scope(scope);
		CalendarKind newKind = kind == null ? null : kind(kind);
		boolean moved = start != null;
		translate(() -> {
			Instant now = clock.instant();
			if (newScope != null || newKind != null || allDay != null) {
				entry.reclassify(newScope, newKind, allDay, now);
			}
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
		if (moved) {
			// Um aviso para o horário antigo é pior que aviso nenhum: a pessoa receberia "começa em
			// 5 min" de um compromisso que já foi para semana que vem.
			reminders.clearPendingForEntry(entry.id());
		}
		return entry;
	}

	/** Cancelar não apaga a linha: o `.ics` precisa do mesmo `UID` com `STATUS:CANCELLED`. */
	public CalendarEntry cancel(String ownerId, String entryId) {
		CalendarEntry entry = query.get(ownerId, entryId);
		requireOwnedByTheCalendar(entry, "cancelar");
		if (!entry.active()) {
			// Repetir o cancelamento é o que o botão pode fazer depois de um timeout; não é erro.
			return entry;
		}
		translate(() -> {
			entry.cancel(clock.instant());
			return entry;
		});
		entries.save(entry);
		reminders.clearPendingForEntry(entry.id());
		return entry;
	}

	/**
	 * Entrada com origem pertence ao módulo que a criou: editar ou cancelar por aqui deixaria a
	 * agenda e o módulo dono contando histórias diferentes (a sessão de mentoria seguiria marcada
	 * com outro horário). Quem muda o fato é o módulo dono, que chama o serviço de origem.
	 */
	private static void requireOwnedByTheCalendar(CalendarEntry entry, String action) {
		if (entry.source().present()) {
			throw new ApiException(409, "CALENDAR_ENTRY_SOURCE_OWNED",
				"Nao da para " + action + " um compromisso criado por outro recurso; "
					+ "altere no modulo de origem.");
		}
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
