package com.socialcoisa.rede.events.adapters.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/api/events")
class EventContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final EventService eventService;
	private final CursorCodec cursorCodec;

	EventContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			EventService eventService, CursorCodec cursorCodec) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.eventService = eventService;
		this.cursorCodec = cursorCodec;
	}

	@GetMapping
	PageResponse<EventView> events(@RequestParam(required = false) String format,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<Event> events = eventService.list(format, before, limit);
		var items = events.stream()
			.map(e -> new EventView(e.id(), e.title(), e.date(), e.time(), e.format(), e.host(),
				eventService.countAttendees(e.id()), e.cover()))
			.toList();
		String nextCursor = events.size() == limit
			? cursorCodec.encode(events.get(events.size() - 1).createdAt()) : null;
		return new PageResponse<>(items, nextCursor);
	}

	@GetMapping("/{eventId}")
	EventView event(@PathVariable String eventId) {
		Event e = eventService.getById(eventId);
		return new EventView(e.id(), e.title(), e.date(), e.time(), e.format(), e.host(),
			eventService.countAttendees(e.id()), e.cover());
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
					request.host(), request.cover(), user.id().value());
				return new CommandResponse(201, new EventView(e.id(), e.title(), e.date(), e.time(), e.format(),
					e.host(), 1, e.cover()));
			});
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
			String cover) {
	}

	record CreateEventRequest(String title, String date, String time, String format, String host, String cover) {
	}

}
