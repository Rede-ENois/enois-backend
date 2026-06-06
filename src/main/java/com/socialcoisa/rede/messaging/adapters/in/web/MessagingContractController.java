package com.socialcoisa.rede.messaging.adapters.in.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
import com.socialcoisa.rede.messaging.application.MessagingService;
import com.socialcoisa.rede.messaging.domain.Conversation;
import com.socialcoisa.rede.messaging.domain.ConversationParticipant;
import com.socialcoisa.rede.messaging.domain.Message;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/api/conversations")
class MessagingContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final MessagingService messagingService;

	MessagingContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			MessagingService messagingService) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.messagingService = messagingService;
	}

	@GetMapping
	PageResponse<ConversationView> conversations(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		List<Conversation> conversations = messagingService.listConversations(user.id().value(), limit);
		var items = conversations.stream()
			.map(c -> {
				List<String> pIds = messagingService.getParticipants(c.id()).stream()
					.map(ConversationParticipant::userId)
					.toList();
				return new ConversationView(c.id(), user.id().value(), c.kind(), c.title(), pIds,
					messagingService.getLastMessageText(c.id()),
					messagingService.getLastMessageTime(c.id()) != null
						? messagingService.getLastMessageTime(c.id()).toString() : null,
					messagingService.countUnread(c.id(), user.id().value()));
			})
			.toList();
		return new PageResponse<>(items, null);
	}

	@GetMapping("/search")
	PageResponse<ConversationView> search(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		List<Conversation> conversations = messagingService.listConversations(user.id().value(), limit);
		String query = q != null ? q.toLowerCase() : "";
		var items = conversations.stream()
			.filter(c -> query.isEmpty() || (c.title() != null && c.title().toLowerCase().contains(query)))
			.map(c -> {
				List<String> pIds = messagingService.getParticipants(c.id()).stream()
					.map(ConversationParticipant::userId)
					.toList();
				return new ConversationView(c.id(), user.id().value(), c.kind(), c.title(), pIds,
					messagingService.getLastMessageText(c.id()),
					messagingService.getLastMessageTime(c.id()) != null
						? messagingService.getLastMessageTime(c.id()).toString() : null,
					messagingService.countUnread(c.id(), user.id().value()));
			})
			.toList();
		return new PageResponse<>(items, null);
	}

	@GetMapping("/{conversationId}")
	ConversationDetailView conversation(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String conversationId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		Conversation c = messagingService.getConversation(conversationId);
		List<String> pIds = messagingService.getParticipants(c.id()).stream()
			.map(ConversationParticipant::userId)
			.toList();
		return new ConversationDetailView(c.id(), c.kind(), c.title(), pIds,
			messagingService.getLastMessageText(c.id()),
			messagingService.countUnread(c.id(), user.id().value()));
	}

	@GetMapping("/{conversationId}/messages")
	PageResponse<MessageView> messages(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String conversationId, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "50") int limit) {
		currentUserResolver.resolve(userId, sessionToken);
		List<Message> msgs = messagingService.getMessages(conversationId, cursor, limit);
		var items = msgs.stream()
			.map(m -> new MessageView(m.id(), m.conversationId(), m.senderId(), m.text(),
				m.createdAt() != null ? m.createdAt().toString() : null))
			.toList();
		return new PageResponse<>(items, null);
	}

	@PostMapping("/{conversationId}/messages")
	ResponseEntity<String> send(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String conversationId, @RequestBody SendMessageRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("conversationId", conversationId, "text", request.text()));
		return idempotency.execute(user.id(), "POST", "/api/conversations/{conversationId}/messages", idempotencyKey,
			hash, () -> {
				Message msg = messagingService.sendMessage(conversationId, user.id().value(), request.text());
				return new CommandResponse(201, new MessageView(msg.id(), msg.conversationId(), msg.senderId(),
					msg.text(), msg.createdAt() != null ? msg.createdAt().toString() : null));
			});
	}

	@PostMapping("/{conversationId}/read")
	ResponseEntity<String> read(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String conversationId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("conversationId", conversationId));
		return idempotency.execute(user.id(), "POST", "/api/conversations/{conversationId}/read", idempotencyKey, hash,
			() -> {
				messagingService.markRead(conversationId, user.id().value());
				return new CommandResponse(200, new StatusResponse(conversationId, "READ"));
			});
	}

	@PostMapping
	ResponseEntity<String> create(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CreateConversationRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/conversations", idempotencyKey, hash,
			() -> {
				Conversation c = messagingService.createConversation(request.kind(), request.title(),
					request.participantIds(), user.id().value());
				List<String> pIds = messagingService.getParticipants(c.id()).stream()
					.map(ConversationParticipant::userId)
					.toList();
				return new CommandResponse(201, new ConversationDetailView(c.id(), c.kind(), c.title(), pIds,
					messagingService.getLastMessageText(c.id()),
					messagingService.countUnread(c.id(), user.id().value())));
			});
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

}
