package com.socialcoisa.rede.messaging.adapters.in.web;

import java.time.Instant;
import java.util.LinkedHashMap;
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

import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.messaging.application.MessagingService;
import com.socialcoisa.rede.messaging.application.port.out.MessagingRepository;
import com.socialcoisa.rede.messaging.domain.Conversation;
import com.socialcoisa.rede.messaging.domain.ConversationParticipant;
import com.socialcoisa.rede.messaging.domain.Message;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/conversations")
class MessagingContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final MessagingService messagingService;
	private final CursorCodec cursorCodec;
	private final com.socialcoisa.rede.shared.blocks.BlockedUsersReader blockedUsersReader;

	MessagingContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			MessagingService messagingService, CursorCodec cursorCodec,
			com.socialcoisa.rede.shared.blocks.BlockedUsersReader blockedUsersReader) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.messagingService = messagingService;
		this.cursorCodec = cursorCodec;
		this.blockedUsersReader = blockedUsersReader;
	}

	@GetMapping
	PageResponse<ConversationView> conversations(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<Conversation> conversations = messagingService.listConversations(user.id().value(), before, limit);
		var items = conversations.stream()
			.map(c -> {
				var participants = messagingService.getParticipants(c.id());
				List<String> pIds = participants.stream()
					.map(ConversationParticipant::userId)
					.toList();
				return toView(c.id(), user.id().value(), c.kind(), c.title(), pIds,
					messagingService.countUnread(c.id(), user.id().value()),
					otherLastReadAt(participants, user.id().value()));
			})
			.toList();
		String nextCursor = conversations.size() == limit
			? cursorCodec.encode(conversations.get(conversations.size() - 1).updatedAt()) : null;
		return new PageResponse<>(items, nextCursor);
	}

	@GetMapping("/unread-count")
	Map<String, Integer> unreadCount(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return Map.of("count", messagingService.countTotalUnread(user.id().value()));
	}

	@GetMapping("/search")
	PageResponse<ConversationView> search(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<Conversation> conversations = messagingService.listConversations(user.id().value(), null, limit);
		String query = q != null ? q.toLowerCase() : "";
		var items = conversations.stream()
			.filter(c -> query.isEmpty() || (c.title() != null && c.title().toLowerCase().contains(query)))
			.map(c -> {
				var participants = messagingService.getParticipants(c.id());
				List<String> pIds = participants.stream()
					.map(ConversationParticipant::userId)
					.toList();
				return toView(c.id(), user.id().value(), c.kind(), c.title(), pIds,
					messagingService.countUnread(c.id(), user.id().value()),
					otherLastReadAt(participants, user.id().value()));
			})
			.toList();
		return new PageResponse<>(items, null);
	}

	@GetMapping("/{conversationId}")
	ConversationDetailView conversation(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String conversationId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (!messagingService.isParticipant(conversationId, user.id().value())) {
			throw new ApiException(403, "NOT_PARTICIPANT", "Voce nao participa desta conversa.");
		}
		Conversation c = messagingService.getConversation(conversationId);
		var participants = messagingService.getParticipants(c.id());
		List<String> pIds = participants.stream()
			.map(ConversationParticipant::userId)
			.toList();
		return toDetailView(c.id(), c.kind(), c.title(), pIds,
			messagingService.countUnread(c.id(), user.id().value()),
			otherLastReadAt(participants, user.id().value()));
	}

	@GetMapping("/{conversationId}/messages")
	PageResponse<MessageView> messages(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String conversationId, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "50") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (!messagingService.isParticipant(conversationId, user.id().value())) {
			throw new ApiException(403, "NOT_PARTICIPANT", "Voce nao participa desta conversa.");
		}
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<Message> msgs = messagingService.getMessages(conversationId, user.id().value(), before, limit);
		var summaries = messagingService.reactionSummaries(msgs.stream().map(Message::id).toList(),
			user.id().value());
		// Carrega em batch as mensagens citadas (reply) da página, sem N+1.
		java.util.Set<String> replyIds = msgs.stream()
			.map(Message::replyToId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
		java.util.Map<String, Message> repliedMessages = replyIds.isEmpty() ? java.util.Map.of()
			: messagingService.findMessagesByIds(replyIds).stream()
				.collect(java.util.stream.Collectors.toMap(Message::id, java.util.function.Function.identity()));
		var items = msgs.stream()
			.map(m -> {
				var summary = summaries.get(m.id());
				ReplyView replyView = null;
				if (m.replyToId() != null) {
					Message replied = repliedMessages.get(m.replyToId());
					if (replied != null) {
						String replyText = replied.text().length() > 100
							? replied.text().substring(0, 97) + "..." : replied.text();
						replyView = new ReplyView(replied.id(), replied.senderId(), replyText, replied.e2e());
					}
				}
				return new MessageView(m.id(), m.conversationId(), m.senderId(), m.text(), m.mediaIds(),
					m.createdAt() != null ? m.createdAt().toString() : null,
					summary == null ? java.util.Map.of() : summary.counts(),
					summary == null ? null : summary.myReaction(), m.mediaSpoiler(),
					m.e2e(), m.iv(), m.replyToId(), replyView);
			})
			.toList();
		// Pagina cheia => ainda ha mensagens mais antigas: o cursor aponta para a mais antiga desta pagina.
		String nextCursor = msgs.size() == limit && !msgs.isEmpty()
			? cursorCodec.encode(msgs.get(msgs.size() - 1).createdAt()) : null;
		return new PageResponse<>(items, nextCursor);
	}

	/**
	 * "Apagar para mim" por MENSAGEM: some so para quem apagou (o outro participante mantem).
	 *
	 * Diferente do apagar a conversa inteira (`/conversations/hide`), aqui a pessoa escolhe o que
	 * sumiu — mesmo padrao do inbox: selecionar, selecionar tudo, apagar.
	 */
	@PostMapping("/{conversationId}/messages/hide")
	ResponseEntity<String> hideMessages(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String conversationId, @RequestBody HideConversationsRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (!messagingService.isParticipant(conversationId, user.id().value())) {
			throw new ApiException(403, "NOT_PARTICIPANT", "Voce nao participa desta conversa.");
		}
		List<String> ids = request.ids() == null ? List.of()
			: request.ids().stream().filter(id -> id != null && !id.isBlank()).map(String::trim).distinct().toList();
		if (ids.isEmpty()) {
			throw new ApiException(400, "EMPTY_MESSAGE_LIST", "Selecione ao menos uma mensagem.");
		}
		String hash = idempotency.hash(Map.of("conversationId", conversationId, "ids", ids));
		return idempotency.execute(user.id(), "POST", "/api/conversations/{conversationId}/messages/hide",
			idempotencyKey, hash, () -> {
				int hidden = messagingService.hideMessages(conversationId, user.id().value(), ids);
				return new CommandResponse(200, Map.of("hidden", hidden));
			});
	}

	@PostMapping("/{conversationId}/messages")
	ResponseEntity<String> send(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String conversationId, @RequestBody SendMessageRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("conversationId", conversationId, "text",
			request.text() == null ? "" : request.text(), "mediaIds",
			request.mediaIds() == null ? List.of() : request.mediaIds(), "mediaSpoiler",
			Boolean.TRUE.equals(request.mediaSpoiler())));
		return idempotency.execute(user.id(), "POST", "/api/conversations/{conversationId}/messages", idempotencyKey,
			hash, () -> {
				Message msg;
				String replyTo = request.replyToId() != null && !request.replyToId().isBlank()
					? request.replyToId().trim() : null;
				if (Boolean.TRUE.equals(request.e2e())) {
					msg = messagingService.sendE2eMessage(conversationId, user.id().value(), request.text(),
						request.iv(), request.mediaIds(), Boolean.TRUE.equals(request.mediaSpoiler()), replyTo);
				}
				else {
					msg = messagingService.sendMessage(conversationId, user.id().value(), request.text(),
						request.mediaIds(), Boolean.TRUE.equals(request.mediaSpoiler()), replyTo);
				}
				return new CommandResponse(201, new MessageView(msg.id(), msg.conversationId(), msg.senderId(),
					msg.text(), msg.mediaIds(),
					msg.createdAt() != null ? msg.createdAt().toString() : null, java.util.Map.of(), null,
					msg.mediaSpoiler(), msg.e2e(), msg.iv(), msg.replyToId(), null));
			});
	}

	@PostMapping("/{conversationId}/read")
	ResponseEntity<String> read(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String conversationId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("conversationId", conversationId));
		return idempotency.execute(user.id(), "POST", "/api/conversations/{conversationId}/read", idempotencyKey, hash,
			() -> {
				messagingService.markRead(conversationId, user.id().value());
				return new CommandResponse(200, new StatusResponse(conversationId, "READ"));
			});
	}

	/**
	 * Abre (ou reaproveita) a conversa direta com um usuario — e o que o botao "Mensagem" do perfil
	 * chama. Nao cria conversa duplicada: devolve a existente quando ja ha uma entre os dois.
	 */
	@PostMapping("/direct")
	ResponseEntity<String> direct(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody DirectConversationRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String otherId = request.userId() == null ? "" : request.userId().trim();
		if (otherId.isEmpty()) {
			throw new ApiException(400, "INVALID_RECIPIENT", "Destinatario obrigatorio.");
		}
		if (otherId.equals(user.id().value())) {
			throw new ApiException(400, "INVALID_RECIPIENT", "Nao da para abrir uma conversa consigo mesmo.");
		}
		// Bloqueio vale nos dois sentidos: ninguem fala com quem bloqueou (ou foi bloqueado).
		if (blockedUsersReader.hiddenIdsFor(user.id().value()).contains(otherId)
				|| blockedUsersReader.hiddenIdsFor(otherId).contains(user.id().value())) {
			throw new ApiException(403, "USER_BLOCKED", "Nao e possivel conversar com este usuario.");
		}
		Map<String, Object> payload = Map.of("direct", otherId);
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "POST", "/api/conversations/direct", idempotencyKey, hash, () -> {
			String conversationId = messagingService.directConversationBetween(user.id().value(), otherId);
			var participants = messagingService.getParticipants(conversationId);
			List<String> pIds = participants.stream()
				.map(ConversationParticipant::userId)
				.toList();
			return new CommandResponse(200, toDetailView(conversationId, "DIRECT", null, pIds,
				messagingService.countUnread(conversationId, user.id().value()),
				otherLastReadAt(participants, user.id().value())));
		});
	}

	/**
	 * "Apagar para mim" em massa: esconde as conversas escolhidas SO para quem chama. O outro
	 * participante mantem a conversa. Aceita a lista de ids.
	 */
	@PostMapping("/hide")
	ResponseEntity<String> hide(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody HideConversationsRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<String> ids = request.ids() == null ? List.of()
			: request.ids().stream().filter(id -> id != null && !id.isBlank()).map(String::trim).distinct().toList();
		if (ids.isEmpty()) {
			throw new ApiException(400, "EMPTY_CONVERSATION_LIST", "Selecione ao menos uma conversa.");
		}
		String hash = idempotency.hash(Map.of("ids", ids));
		return idempotency.execute(user.id(), "POST", "/api/conversations/hide", idempotencyKey, hash, () -> {
			int hidden = messagingService.hideConversations(user.id().value(), ids);
			return new CommandResponse(200, Map.of("hidden", hidden));
		});
	}

	/** As conversas que a pessoa apagou para si — a tela de arquivadas. */
	@GetMapping("/archived")
	PageResponse<ConversationView> archived(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<Conversation> conversations = messagingService.listArchivedConversations(user.id().value(), before,
			Math.min(limit, 50));
		var items = conversations.stream()
			.map(c -> {
				var participants = messagingService.getParticipants(c.id());
				List<String> pIds = participants.stream()
					.map(ConversationParticipant::userId)
					.toList();
				return toView(c.id(), user.id().value(), c.kind(), c.title(), pIds,
					messagingService.countUnread(c.id(), user.id().value()),
					otherLastReadAt(participants, user.id().value()));
			})
			.toList();
		String nextCursor = conversations.size() == limit
			? cursorCodec.encode(conversations.get(conversations.size() - 1).updatedAt()) : null;
		return new PageResponse<>(items, nextCursor);
	}

	/** Restaurar: desfaz o "apagar para mim" das conversas escolhidas. */
	@PostMapping("/unhide")
	ResponseEntity<String> unhide(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody HideConversationsRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<String> ids = request.ids() == null ? List.of()
			: request.ids().stream().filter(id -> id != null && !id.isBlank()).map(String::trim).distinct().toList();
		if (ids.isEmpty()) {
			throw new ApiException(400, "EMPTY_CONVERSATION_LIST", "Selecione ao menos uma conversa.");
		}
		String hash = idempotency.hash(Map.of("ids", ids));
		return idempotency.execute(user.id(), "POST", "/api/conversations/unhide", idempotencyKey, hash, () -> {
			int restored = messagingService.unhideConversations(user.id().value(), ids);
			return new CommandResponse(200, Map.of("restored", restored));
		});
	}

	@PostMapping
	ResponseEntity<String> create(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CreateConversationRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/conversations", idempotencyKey, hash,
			() -> {
				Conversation c = messagingService.createConversation(request.kind(), request.title(),
					request.participantIds(), user.id().value());
				var participants = messagingService.getParticipants(c.id());
				List<String> pIds = participants.stream()
					.map(ConversationParticipant::userId)
					.toList();
				return new CommandResponse(201, toDetailView(c.id(), c.kind(), c.title(), pIds,
					messagingService.countUnread(c.id(), user.id().value()),
					otherLastReadAt(participants, user.id().value())));
			});
	}

	@PostMapping("/{conversationId}/messages/{messageId}/reactions")
	ResponseEntity<String> reactToMessage(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String conversationId, @PathVariable String messageId,
			@RequestBody ReactToMessageRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		requireMessageInConversation(conversationId, messageId, user.id().value());
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("messageId", messageId);
		payload.put("type", request.type() == null ? "" : request.type());
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "POST", "/api/conversations/{conversationId}/messages/{messageId}/reactions",
			idempotencyKey, hash, () -> {
				boolean added = messagingService.addReaction(messageId, user.id().value(), request.type());
				return new CommandResponse(200, Map.of("status", added ? "REACTED" : "REMOVED"));
			});
	}

	@GetMapping("/{conversationId}/messages/{messageId}/reactions")
	Object listMessageReactions(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String conversationId, @PathVariable String messageId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		requireMessageInConversation(conversationId, messageId, user.id().value());
		var reactions = messagingService.getReactions(messageId);
		var summary = reactions.stream()
			.collect(Collectors.groupingBy(MessagingRepository.ReactionSummary::type, Collectors.counting()));
		return Map.of("messageId", messageId, "summary", summary, "reactions", reactions);
	}

	/** Participa da conversa E a mensagem e dela: e o que o id da conversa no caminho promete. */
	private void requireMessageInConversation(String conversationId, String messageId, String userId) {
		if (!messagingService.isParticipant(conversationId, userId)) {
			throw new ApiException(403, "NOT_PARTICIPANT", "Voce nao participa desta conversa.");
		}
		if (!messagingService.messageBelongsTo(messageId, conversationId)) {
			throw new ApiException(404, "MESSAGE_NOT_FOUND", "Mensagem nao encontrada nesta conversa.");
		}
	}

	record ReactToMessageRequest(ReactionType type) {
	}

	/** Quem leu por último entre os OUTROS participantes (null = nunca abriu). */
	private static String otherLastReadAt(List<ConversationParticipant> participants, String viewerId) {
		return participants.stream()
			.filter(participant -> !participant.userId().equals(viewerId))
			.map(ConversationParticipant::lastReadAt)
			.filter(java.util.Objects::nonNull)
			.max(java.time.Instant::compareTo)
			.map(java.time.Instant::toString)
			.orElse(null);
	}

	private ConversationView toView(String convId, String userId, String kind, String title,
			List<String> participantIds, int unread, String otherLastReadAt) {
		var lm = messagingService.getLastMessagePreview(convId);
		return new ConversationView(convId, userId, kind, title, participantIds,
			lm.text(), lm.time() != null ? lm.time().toString() : null, unread, otherLastReadAt,
			lm.e2e(), lm.iv());
	}

	private ConversationDetailView toDetailView(String convId, String kind, String title,
			List<String> participantIds, int unread, String otherLastReadAt) {
		var lm = messagingService.getLastMessagePreview(convId);
		return new ConversationDetailView(convId, kind, title, participantIds,
			lm.text(), unread, otherLastReadAt, lm.e2e(), lm.iv());
	}

	record ConversationView(String id, String userId, String kind, String title, List<String> participantIds,
			String lastMessage, String time, int unread, String otherLastReadAt,
			boolean lastMessageE2e, String lastMessageIv) {
	}

	record ConversationDetailView(String id, String kind, String title, List<String> participantIds, String lastMessage,
			int unread, String otherLastReadAt, boolean lastMessageE2e, String lastMessageIv) {
	}

	record MessageView(String id, String conversationId, String from, String text, List<String> mediaIds,
			String time, java.util.Map<String, Integer> reactions, String myReaction, boolean mediaSpoiler,
			boolean e2e, String iv, String replyToId, ReplyView replyTo) {
	}

	record ReplyView(String id, String from, String text, boolean e2e) {
	}

	record CreateConversationRequest(String kind, String title, List<String> participantIds) {
	}

	record DirectConversationRequest(String userId) {
	}

	record HideConversationsRequest(List<String> ids) {
	}

	record SendMessageRequest(String text, List<String> mediaIds, Boolean mediaSpoiler,
			Boolean e2e, String iv, String replyToId) {
	}

}
