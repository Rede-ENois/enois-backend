package com.socialcoisa.rede.events.adapters.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

import com.socialcoisa.rede.events.application.EventRecurrenceService;
import com.socialcoisa.rede.events.application.EventService;
import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/events")
class EventContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final EventService eventService;
	private final CursorCodec cursorCodec;

	private final AdminGuard adminGuard;

	private final EventRecurrenceService recurrenceService;

	EventContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			EventService eventService, CursorCodec cursorCodec, AdminGuard adminGuard,
			EventRecurrenceService recurrenceService) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.eventService = eventService;
		this.cursorCodec = cursorCodec;
		this.adminGuard = adminGuard;
		this.recurrenceService = recurrenceService;
	}

	/**
	 * Apaga um evento. Quem criou pode apagar o seu; administrador apaga qualquer um — e e assim que
	 * um evento absurdo (data que nao existe na vida real) sai da vitrine sem depender de banco.
	 */
	@DeleteMapping("/{eventId}")
	ResponseEntity<String> delete(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String eventId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(java.util.Map.of("eventId", eventId));
		return idempotency.execute(user.id(), "DELETE", "/api/events/{eventId}", idempotencyKey, hash,
			() -> {
				eventService.delete(eventId, user.id().value(), isAdmin(user));
				return new CommandResponse(200, new StatusResponse(eventId, "DELETED"));
			});
	}

	/** O guarda de admin lança 403 para quem nao e: aqui queremos saber sem quebrar o fluxo. */
	private boolean isAdmin(CurrentUser user) {
		try {
			adminGuard.requireAdmin(user);
			return true;
		}
		catch (RuntimeException notAdmin) {
			return false;
		}
	}

	/**
	 * Lista os eventos que ainda vao acontecer; `includePast=true` inclui o historico (com selo) e
	 * `recurring=true` deixa so os rituais (eventos com recorrencia).
	 */
	@GetMapping
	PageResponse<EventView> events(@RequestParam(required = false) String format,
			@RequestParam(required = false) String city, @RequestParam(required = false) String state,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
			@RequestParam(defaultValue = "false") boolean includePast,
			@RequestParam(defaultValue = "false") boolean recurring,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		Instant before = cursorCodec.decode(cursor).orElse(null);
		var page = eventService.list(format, city, state, before, limit, includePast, recurring);
		String viewer = viewerId(sessionToken);
		List<String> ids = page.items().stream().map(Event::id).toList();
		Set<String> attendingIds = viewer != null ? eventService.attendingEventIds(viewer, ids) : Set.of();
		// Presencas da pagina numa consulta so: um count por card era N+1.
		java.util.Map<String, Integer> counts = eventService.countAttendeesByEventIds(ids);
		var items = page.items().stream()
			.map(event -> toView(event, attendingIds.contains(event.id()), viewer,
				counts.getOrDefault(event.id(), 0)))
			.toList();
		// Cursor pela pagina que veio do banco: esconder evento vencido nao pode encerrar a paginacao.
		String nextCursor = page.fetched().size() == limit
			? cursorCodec.encode(page.fetched().get(page.fetched().size() - 1).createdAt()) : null;
		return new PageResponse<>(items, nextCursor);
	}

	private EventView toView(Event event, boolean attending, String viewerId) {
		return toView(event, attending, viewerId, eventService.countAttendees(event.id()));
	}

	/** `viewerId` null = anonimo. So o dono gerencia a serie; o id dele nao sai na resposta. */
	private EventView toView(Event event, boolean attending, String viewerId, int counted) {
		// Depois da sanitizacao as presencas somem: o numero guardado sustenta o total.
		Integer finalCount = event.finalAttendeesCount();
		int attendees = finalCount != null ? finalCount : counted;
		return new EventView(event.id(), event.title(), event.date(), event.time(), event.format(), event.host(),
			attendees, event.cover(), event.streamUrl(), event.location(), eventService.hasEnded(event), attending,
			event.recurrence().name(), event.seriesId(),
			recurrenceService.nextOccurrenceDate(event).map(java.time.LocalDate::toString).orElse(null),
			event.createdBy().equals(viewerId),
			event.place() == null ? null : event.place().city(), event.place() == null ? null : event.place().state());
	}

	@GetMapping("/{eventId}")
	EventView event(@PathVariable String eventId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		Event event = eventService.getById(eventId);
		String viewer = viewerId(sessionToken);
		boolean attending = viewer != null
			&& eventService.attendingEventIds(viewer, List.of(event.id())).contains(event.id());
		return toView(event, attending, viewer);
	}

	@PostMapping
	ResponseEntity<String> create(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CreateEventRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/events", idempotencyKey, hash,
			() -> {
				Event e = eventService.create(request.title(), request.date(), request.time(), request.format(),
					request.host(), request.cover(), request.streamUrl(), request.location(), request.recurrence(),
					request.city(), request.state(), user.id().value());
				return new CommandResponse(201, toView(e, false, user.id().value()));
			});
	}

	/** Edita o ritual. So quem criou; a recorrencia fica no proprio endpoint. */
	@PatchMapping("/{eventId}")
	ResponseEntity<String> update(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String eventId, @RequestBody UpdateEventRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("eventId", eventId, "request", request));
		return idempotency.execute(user.id(), "PATCH", "/api/events/{eventId}", idempotencyKey, hash,
			() -> {
				Event e = eventService.update(eventId, request.title(), request.date(), request.time(),
					request.format(), request.host(), request.cover(), request.streamUrl(), request.location(),
					request.city(), request.state(), user.id().value());
				return new CommandResponse(200, toView(e, false, user.id().value()));
			});
	}

	/** Liga, troca ou desliga a recorrencia (so quem criou). NONE encerra a serie. */
	@PatchMapping("/{eventId}/recurrence")
	ResponseEntity<String> changeRecurrence(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String eventId, @RequestBody RecurrenceRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("eventId", eventId, "recurrence", String.valueOf(request.recurrence())));
		return idempotency.execute(user.id(), "PATCH", "/api/events/{eventId}/recurrence", idempotencyKey, hash,
			() -> {
				Event event = eventService.changeRecurrence(eventId, request.recurrence(), user.id().value());
				return new CommandResponse(200, toView(event, false, user.id().value()));
			});
	}

	/** Resolve o usuario quando ha sessao; listagem publica segue sem login. */
	private String viewerId(String sessionToken) {
		try {
			return currentUserResolver.resolve(sessionToken).id().value();
		}
		catch (RuntimeException exception) {
			return null;
		}
	}

	@PostMapping("/{eventId}/attendees")
	ResponseEntity<String> attend(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String eventId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("eventId", eventId));
		return idempotency.execute(user.id(), "POST", "/api/events/{eventId}/attendees", idempotencyKey, hash,
			() -> {
				eventService.attend(eventId, user.id().value());
				return new CommandResponse(200, new StatusResponse(eventId, "ATTENDING"));
			});
	}

	@DeleteMapping("/{eventId}/attendees")
	ResponseEntity<String> cancel(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String eventId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("eventId", eventId));
		return idempotency.execute(user.id(), "DELETE", "/api/events/{eventId}/attendees", idempotencyKey, hash,
			() -> {
				eventService.cancelAttendance(eventId, user.id().value());
				return new CommandResponse(200, new StatusResponse(eventId, "NOT_ATTENDING"));
			});
	}

	record EventView(String id, String title, String date, String time, String format, String host, int attendees,
			String cover, String streamUrl, String location, boolean expired, boolean attending, String recurrence,
			String seriesId, String nextOccurrenceDate, boolean canManageRecurrence, String city, String state) {
	}

	record CreateEventRequest(String title, String date, String time, String format, String host, String cover,
			String streamUrl, String location, String recurrence, String city, String state) {
	}

	record UpdateEventRequest(String title, String date, String time, String format, String host, String cover,
			String streamUrl, String location, String city, String state) {
	}

	record RecurrenceRequest(String recurrence) {
	}

}
