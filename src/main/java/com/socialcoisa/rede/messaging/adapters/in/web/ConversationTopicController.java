package com.socialcoisa.rede.messaging.adapters.in.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.messaging.application.ConversationTopicService;
import com.socialcoisa.rede.messaging.domain.ConversationTopic;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.PageResponse;

/**
 * Topicos do grupo de conversa (issue #915). Fica num controller proprio: o contrato de tópicos
 * tem rotas e guardas proprias, e o controller da conversa ja carrega o contrato inteiro dela.
 */
@RestController
@RequestMapping("/conversations/{conversationId}/group/topics")
class ConversationTopicController {

	private final CurrentUserResolver currentUserResolver;
	private final ConversationTopicService topics;
	private final IdempotencyApplicationService idempotency;

	ConversationTopicController(CurrentUserResolver currentUserResolver, ConversationTopicService topics,
			IdempotencyApplicationService idempotency) {
		this.currentUserResolver = currentUserResolver;
		this.topics = topics;
		this.idempotency = idempotency;
	}

	@GetMapping
	PageResponse<TopicView> list(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String conversationId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return new PageResponse<>(topics.listTopics(conversationId, user.id().value()).stream()
			.map(ConversationTopicController::toView).toList(), null);
	}

	/** Cria um topico (dono ou administrador). 201 com o topico criado. */
	@PostMapping
	ResponseEntity<String> create(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String conversationId, @RequestBody TopicRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("conversationId", conversationId);
		payload.put("name", request.name() == null ? "" : request.name());
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "POST", "/api/conversations/{conversationId}/group/topics",
			idempotencyKey, hash, () -> {
				ConversationTopic created = topics.createTopic(conversationId, user.id().value(), request.name());
				return new CommandResponse(201, toView(created));
			});
	}

	/** Renomeia o topico; sem nome no corpo, nada muda (idempotente). */
	@PatchMapping("/{topicId}")
	ResponseEntity<String> rename(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String conversationId, @PathVariable String topicId, @RequestBody TopicRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("conversationId", conversationId);
		payload.put("topicId", topicId);
		payload.put("name", request.name() == null ? "" : request.name());
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "PATCH", "/api/conversations/{conversationId}/group/topics/{topicId}",
			idempotencyKey, hash, () -> {
				ConversationTopic renamed = topics.renameTopic(conversationId, user.id().value(), topicId,
					request.name());
				return new CommandResponse(200, toView(renamed));
			});
	}

	@PostMapping("/{topicId}/archive")
	ResponseEntity<String> archive(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String conversationId, @PathVariable String topicId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("conversationId", conversationId, "topicId", topicId));
		return idempotency.execute(user.id(), "POST",
			"/api/conversations/{conversationId}/group/topics/{topicId}/archive", idempotencyKey, hash, () -> {
				ConversationTopic archived = topics.archiveTopic(conversationId, user.id().value(), topicId);
				return new CommandResponse(200, toView(archived));
			});
	}

	/** A lista inteira numa nova ordem: precisa conter todos os topicos do grupo, uma vez cada. */
	@PostMapping("/reorder")
	ResponseEntity<String> reorder(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String conversationId, @RequestBody ReorderTopicsRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<String> ids = request.topicIds() == null ? List.of() : request.topicIds();
		String hash = idempotency.hash(Map.of("conversationId", conversationId, "topicIds", ids));
		return idempotency.execute(user.id(), "POST", "/api/conversations/{conversationId}/group/topics/reorder",
			idempotencyKey, hash, () -> {
				List<ConversationTopic> ordered = topics.reorderTopics(conversationId, user.id().value(), ids);
				return new CommandResponse(200, new PageResponse<>(ordered.stream()
					.map(ConversationTopicController::toView).toList(), null));
			});
	}

	private static TopicView toView(ConversationTopic topic) {
		return new TopicView(topic.id(), topic.conversationId(), topic.name(), topic.position(),
			topic.status().name(), topic.createdAt(), topic.updatedAt());
	}

	record TopicView(String id, String conversationId, String name, int position, String status, Instant createdAt,
			Instant updatedAt) {
	}

	record TopicRequest(String name) {
	}

	record ReorderTopicsRequest(List<String> topicIds) {
	}

}
