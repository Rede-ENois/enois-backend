package com.socialcoisa.rede.calendar.adapters.in.web;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.calendar.application.CalendarQueryService;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Leitura da agenda do próprio usuário. Não existe rota pública: sem sessão a resposta é 401, e o
 * identificador de outra pessoa não é distinguível de um que não existe.
 *
 * <p>Os instantes entram e saem em ISO-8601 UTC. O fuso do organizador viaja junto na entrada, para
 * a tela mostrar "às 19h" mesmo quando a leitura acontece em outro fuso.
 */
@RestController
@RequestMapping("/calendar/entries")
class CalendarEntryController {

	private final CurrentUserResolver currentUserResolver;
	private final CalendarQueryService calendarQueryService;

	CalendarEntryController(CurrentUserResolver currentUserResolver, CalendarQueryService calendarQueryService) {
		this.currentUserResolver = currentUserResolver;
		this.calendarQueryService = calendarQueryService;
	}

	@GetMapping
	List<CalendarEntryView> entries(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(required = false) String scope) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return calendarQueryService
			.inWindow(user.id().value(), instant(from, "from"), instant(to, "to"), scope(scope))
			.stream()
			.map(CalendarEntryView::from)
			.toList();
	}

	@GetMapping("/{entryId}")
	CalendarEntryView entry(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String entryId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return CalendarEntryView.from(calendarQueryService.get(user.id().value(), entryId));
	}

	private static Instant instant(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			throw new ApiException(400, "INVALID_WINDOW", "Informe " + field + " em ISO-8601.");
		}
		try {
			return Instant.parse(raw.trim());
		} catch (DateTimeParseException exception) {
			throw new ApiException(400, "INVALID_WINDOW", field + " precisa ser um instante ISO-8601.");
		}
	}

	private static CalendarScope scope(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return CalendarScope.parse(raw.trim());
		} catch (IllegalArgumentException exception) {
			throw new ApiException(400, "INVALID_SCOPE", "Agenda invalida: use SOCIAL ou PROFESSIONAL.");
		}
	}

	/** O contrato de saída não expõe a entidade nem o dono: quem lê já é o dono. */
	record CalendarEntryView(String id, String scope, String kind, String title, String description,
			String startsAt, String endsAt, String timeZoneId, boolean allDay, String state,
			String sourceType, String sourceId, String createdAt, String updatedAt, String cancelledAt) {

		static CalendarEntryView from(CalendarEntry entry) {
			return new CalendarEntryView(entry.id(), entry.scope().name(), entry.kind().name(), entry.title(),
				entry.description(), entry.startsAt().toString(), entry.endsAt().toString(), entry.zone().getId(),
				entry.allDay(), entry.state().name(), entry.source().type(), entry.source().id(),
				entry.createdAt().toString(), entry.updatedAt().toString(),
				entry.cancelledAt() == null ? null : entry.cancelledAt().toString());
		}
	}
}
