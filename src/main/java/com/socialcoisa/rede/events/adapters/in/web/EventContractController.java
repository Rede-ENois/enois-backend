package com.socialcoisa.rede.events.adapters.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

	EventContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			EventService eventService, CursorCodec cursorCodec, AdminGuard adminGuard) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.eventService = eventService;
		this.cursorCodec = cursorCodec;
		this.adminGuard = adminGuard;
	}

	/**
	 * Apaga um evento. Quem criou pode apagar o seu; administrador apaga qualquer um — e e assim que
	 * um evento absurdo (data que nao existe na vida real) sai da vitrine sem depender de banco.
	 */
	@DeleteMapping("/{eventId}")
	ResponseEntity<String> delete(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String eventId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
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

	/** Lista os eventos que ainda vao acontecer; `includePast=true` inclui o historico (com selo). */
	@GetMapping
	PageResponse<EventView> events(@RequestParam(required = false) String format,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
			@RequestParam(defaultValue = "false") boolean includePast,
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		Instant before = cursorCodec.decode(cursor).orElse(null);
		var page = eventService.list(format, before, limit, includePast);
		String viewer = viewerId(userId, sessionToken);
		List<String> ids = page.items().stream().map(Event::id).toList();
		Set<String> attendingIds = viewer != null ? eventService.attendingEventIds(viewer, ids) : Set.of();
		var items = page.items().stream().map(event -> toView(event, attendingIds.contains(event.id()))).toList();
		// Cursor pela pagina que veio do banco: esconder evento vencido nao pode encerrar a paginacao.
		String nextCursor = page.fetched().size() == limit
			? cursorCodec.encode(page.fetched().get(page.fetched().size() - 1).createdAt()) : null;
		return new PageResponse<>(items, nextCursor);
	}

	private EventView toView(Event event, boolean attending) {
		// Depois da sanitizacao as presencas somem: o numero guardado sustenta o total.
		Integer finalCount = event.finalAttendeesCount();
		int attendees = finalCount != null ? finalCount : eventService.countAttendees(event.id());
		return new EventView(event.id(), event.title(), event.date(), event.time(), event.format(), event.host(),
			attendees, event.cover(), event.streamUrl(), event.location(), eventService.hasEnded(event), attending);
	}

	@GetMapping("/{eventId}")
	EventView event(@PathVariable String eventId,
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		Event event = eventService.getById(eventId);
		String viewer = viewerId(userId, sessionToken);
		boolean attending = viewer != null
			&& eventService.attendingEventIds(viewer, List.of(event.id())).contains(event.id());
		return toView(event, attending);
	}

	@PostMapping
	ResponseEntity<String> create(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CreateEventRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/events", idempotencyKey, hash,
			() -> {
				Event e = eventService.create(request.title(), request.date(), request.time(), request.format(),
					request.host(), request.cover(), request.streamUrl(), request.location(),
					user.id().value());
				return new CommandResponse(201, toView(e, false));
			});
	}

	/** Resolve o usuario quando ha sessao; listagem publica segue sem login. */
	private String viewerId(String userId, String sessionToken) {
		try {
			return currentUserResolver.resolve(userId, sessionToken).id().value();
		}
		catch (RuntimeException exception) {
			return null;
		}
	}

	@PostMapping("/{eventId}/attendees")
	ResponseEntity<String> attend(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String eventId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("eventId", eventId));
		return idempotency.execute(user.id(), "POST", "/api/events/{eventId}/attendees", idempotencyKey, hash,
			() -> {
				eventService.attend(eventId, user.id().value());
				return new CommandResponse(200, new StatusResponse(eventId, "ATTENDING"));
			});
	}

	@DeleteMapping("/{eventId}/attendees")
	ResponseEntity<String> cancel(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String eventId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("eventId", eventId));
		return idempotency.execute(user.id(), "DELETE", "/api/events/{eventId}/attendees", idempotencyKey, hash,
			() -> {
				eventService.cancelAttendance(eventId, user.id().value());
				return new CommandResponse(200, new StatusResponse(eventId, "NOT_ATTENDING"));
			});
	}

	record EventView(String id, String title, String date, String time, String format, String host, int attendees,
			String cover, String streamUrl, String location, boolean expired, boolean attending) {
	}

	record CreateEventRequest(String title, String date, String time, String format, String host, String cover,
			String streamUrl, String location) {
	}

}
