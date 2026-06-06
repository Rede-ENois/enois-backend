package com.socialcoisa.rede.messaging.adapters.in.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
@RequestMapping("/api/conversations")
class MessagingContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;

	MessagingContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping
	PageResponse<ConversationView> conversations(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		currentUserResolver.resolve(userId, sessionToken);
		return new PageResponse<>(List.of(), null);
	}

	@GetMapping("/search")
	PageResponse<ConversationView> search(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "20") int limit) {
		currentUserResolver.resolve(userId, sessionToken);
		return new PageResponse<>(List.of(), null);
	}

	@GetMapping("/{conversationId}")
	ConversationDetailView conversation(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String conversationId) {
		currentUserResolver.resolve(userId, sessionToken);
		return new ConversationDetailView(conversationId, null, "", List.of(), null, 0);
	}

	@GetMapping("/{conversationId}/messages")
	PageResponse<MessageView> messages(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String conversationId, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "50") int limit) {
		currentUserResolver.resolve(userId, sessionToken);
		return new PageResponse<>(List.of(), null);
	}

	@PostMapping("/{conversationId}/messages")
	ResponseEntity<String> send(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String conversationId, @RequestBody SendMessageRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(new SendMessagePayload(conversationId, request));
		return idempotency.execute(user.id(), "POST", "/api/conversations/{conversationId}/messages", idempotencyKey,
			hash, () -> new CommandResponse(201, new MessageView(newId("message"), conversationId, user.id().value(),
				request.text(), null)));
	}

	@PostMapping("/{conversationId}/read")
	ResponseEntity<String> read(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String conversationId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(new ConversationPayload(conversationId));
		return idempotency.execute(user.id(), "POST", "/api/conversations/{conversationId}/read", idempotencyKey, hash,
			() -> new CommandResponse(200, new StatusResponse(conversationId, "READ")));
	}

	@PostMapping
	ResponseEntity<String> create(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CreateConversationRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/conversations", idempotencyKey, hash,
			() -> new CommandResponse(201, new ConversationDetailView(newId("conversation"), request.kind(),
				request.title(), request.participantIds(), null, 0)));
	}

	private String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}

	record ConversationView(String id, String userId, String kind, String title, List<String> participantIds,
			String lastMessage, String time, int unread) {
	}

	record ConversationDetailView(String id, String kind, String title, List<String> participantIds, String lastMessage,
			int unread) {
	}

	record MessageView(String id, String conversationId, String from, String text, String time) {
	}

	record CreateConversationRequest(String kind, String title, List<String> participantIds) {
	}

	record SendMessageRequest(String text) {
	}

	record SendMessagePayload(String conversationId, SendMessageRequest request) {
	}

	record ConversationPayload(String conversationId) {
	}
}
