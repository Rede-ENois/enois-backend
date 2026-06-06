package com.socialcoisa.rede.events.adapters.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/api/events")
class EventContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;

	EventContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping
	PageResponse<EventView> events(@RequestParam(required = false) String format,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		return new PageResponse<>(List.of(), null);
	}

	@GetMapping("/{eventId}")
	EventView event(@PathVariable String eventId) {
		return new EventView(eventId, "", null, null, "", "", 0, null);
	}

	@PostMapping("/{eventId}/attendees")
	ResponseEntity<String> attend(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String eventId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("eventId", eventId));
		return idempotency.execute(user.id(), "POST", "/api/events/{eventId}/attendees", idempotencyKey, hash,
			() -> new CommandResponse(200, new StatusResponse(eventId, "ATTENDING")));
	}

	@DeleteMapping("/{eventId}/attendees")
	ResponseEntity<String> cancel(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String eventId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("eventId", eventId));
		return idempotency.execute(user.id(), "DELETE", "/api/events/{eventId}/attendees", idempotencyKey, hash,
			() -> new CommandResponse(200, new StatusResponse(eventId, "NOT_ATTENDING")));
	}

	record EventView(String id, String title, String date, String time, String format, String host, int attendees,
			String cover) {
	}
}
