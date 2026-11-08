package com.socialcoisa.rede.calendar.adapters.in.web;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.calendar.application.CalendarCommandService;
import com.socialcoisa.rede.calendar.application.CalendarQueryService;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Agenda do próprio usuário: leitura e escrita. Não existe rota pública, sem sessão a resposta é
 * 401, e o identificador de outra pessoa não é distinguível de um que não existe.
 *
 * <p>Toda escrita exige `Idempotency-Key`, e a rota entra na chave do registro: a mesma chave
 * repetida devolve a resposta guardada em vez de criar ou cancelar duas vezes.
 *
 * <p>Os instantes entram e saem em ISO-8601 UTC. O fuso do organizador viaja junto na entrada, para
 * a tela mostrar "às 19h" mesmo quando a leitura acontece em outro fuso.
 */
@RestController
@RequestMapping("/calendar/entries")
class CalendarEntryController {

	private final CurrentUserResolver currentUserResolver;
	private final CalendarQueryService calendarQueryService;
	private final CalendarCommandService calendarCommandService;
	private final IdempotencyApplicationService idempotency;

	CalendarEntryController(CurrentUserResolver currentUserResolver, CalendarQueryService calendarQueryService,
			CalendarCommandService calendarCommandService, IdempotencyApplicationService idempotency) {
		this.currentUserResolver = currentUserResolver;
		this.calendarQueryService = calendarQueryService;
		this.calendarCommandService = calendarCommandService;
		this.idempotency = idempotency;
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

	/**
	 * Criação. A linha nasce sem origem: quem grava entrada de outro módulo (evento, mentoria) usa o
	 * serviço de aplicação, com a origem preenchida, e não esta rota.
	 */
	@PostMapping
	ResponseEntity<String> create(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = false) CalendarEntryRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (request == null) {
			throw new ApiException(400, "INVALID_CALENDAR_ENTRY", "Informe os dados do compromisso.");
		}
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id().value(), "POST", "/api/calendar/entries", idempotencyKey, hash,
			() -> {
				CalendarEntry entry = calendarCommandService.create(user.id().value(), request.scope(),
					request.kind(), request.title(), request.description(), request.startsAt(), request.endsAt(),
					request.timeZoneId(), request.allDay());
				return new CommandResponse(201, CalendarEntryView.from(entry));
			});
	}

	@PatchMapping("/{entryId}")
	ResponseEntity<String> update(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String entryId, @RequestBody(required = false) CalendarEntryPatchRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (request == null) {
			throw new ApiException(400, "INVALID_CALENDAR_ENTRY", "Informe os campos a alterar.");
		}
		String hash = idempotency.hash(Map.of("entryId", entryId, "request", request));
		return idempotency.execute(user.id().value(), "PATCH", "/api/calendar/entries/{entryId}", idempotencyKey,
			hash, () -> {
				CalendarEntry entry = calendarCommandService.update(user.id().value(), entryId, request.title(),
					request.description(), request.startsAt(), request.endsAt(), request.timeZoneId());
				return new CommandResponse(200, CalendarEntryView.from(entry));
			});
	}

	/** Cancela, não apaga: o `.ics` publica `STATUS:CANCELLED` com o mesmo `UID`. */
	@DeleteMapping("/{entryId}")
	ResponseEntity<String> cancel(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String entryId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("entryId", entryId));
		return idempotency.execute(user.id().value(), "DELETE", "/api/calendar/entries/{entryId}", idempotencyKey,
			hash, () -> new CommandResponse(200,
				CalendarEntryView.from(calendarCommandService.cancel(user.id().value(), entryId))));
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

	/** Corpo da criação: `scope` e `kind` têm padrão (SOCIAL e PERSONAL) para o app não mandar sempre. */
	record CalendarEntryRequest(String scope, String kind, String title, String description, String startsAt,
			String endsAt, String timeZoneId, boolean allDay) {
	}

	/** Corpo da edição parcial: campo nulo ou ausente não muda; `description` vazia limpa o texto. */
	record CalendarEntryPatchRequest(String title, String description, String startsAt, String endsAt,
			String timeZoneId) {
	}
}
