package com.socialcoisa.rede.messaging.adapters.in.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Server-sent events for the inbox: the browser keeps one stream open and refetches a conversation
 * when told it changed, instead of polling every few seconds. Authenticated by the session cookie
 * (EventSource sends cookies, not custom headers), so it is a GET with no side effects.
 */
@RestController
class MessageStreamController {

	private final CurrentUserResolver currentUserResolver;
	private final MessageStreamHub hub;

	MessageStreamController(CurrentUserResolver currentUserResolver, MessageStreamHub hub) {
		this.currentUserResolver = currentUserResolver;
		this.hub = hub;
	}

	@GetMapping(path = "/conversations/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	ResponseEntity<SseEmitter> stream(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		String userId;
		try {
			userId = currentUserResolver.resolve(sessionToken).id().value();
		}
		catch (ApiException rejected) {
			// The client only accepts text/event-stream, so the usual JSON error body cannot be written;
			// the bare status is what EventSource needs to stop reconnecting.
			return ResponseEntity.status(rejected.status()).build();
		}
		return ResponseEntity.ok()
			// nginx-style gateways buffer responses by default, which would hold every event back.
			.header("X-Accel-Buffering", "no")
			.body(hub.open(userId));
	}
}
