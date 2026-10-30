package com.socialcoisa.rede.messaging.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.messaging.application.port.out.MessagingRepository;
import com.socialcoisa.rede.messaging.domain.Conversation;
import com.socialcoisa.rede.messaging.domain.ConversationParticipant;
import com.socialcoisa.rede.messaging.domain.Message;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.shared.security.BehavioralTimingGuard;
import com.socialcoisa.rede.shared.security.UserRateLimiter;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class MessagingService {

	private final MessagingRepository repository;
	private final NotificationService notificationService;
	private final Clock clock;
	private final UserRateLimiter userRateLimiter;
	private final BehavioralTimingGuard behavioralTimingGuard;
	private final com.socialcoisa.rede.shared.age.AgePolicyReader agePolicy;
	private final com.socialcoisa.rede.shared.age.AgeGate ageGate;
	private final com.socialcoisa.rede.shared.sanctions.SanctionGate sanctionGate;
	private final com.socialcoisa.rede.network.application.port.out.ConnectionRepository connections;
	private final com.socialcoisa.rede.shared.blocks.BlockedUsersReader blockedUsers;

	public MessagingService(MessagingRepository repository, NotificationService notificationService, Clock clock,
			UserRateLimiter userRateLimiter, BehavioralTimingGuard behavioralTimingGuard,
			com.socialcoisa.rede.shared.age.AgePolicyReader agePolicy, com.socialcoisa.rede.shared.age.AgeGate ageGate,
			com.socialcoisa.rede.shared.sanctions.SanctionGate sanctionGate,
			com.socialcoisa.rede.network.application.port.out.ConnectionRepository connections,
			com.socialcoisa.rede.shared.blocks.BlockedUsersReader blockedUsers) {
		this.repository = repository;
		this.notificationService = notificationService;
		this.clock = clock;
		this.userRateLimiter = userRateLimiter;
		this.behavioralTimingGuard = behavioralTimingGuard;
		this.agePolicy = agePolicy;
		this.ageGate = ageGate;
		this.sanctionGate = sanctionGate;
		this.connections = connections;
		this.blockedUsers = blockedUsers;
	}

	@Transactional
	public Conversation createConversation(String kind, String title, List<String> participantIds, String createdBy) {
		// Validação comum a TODO kind (DIRECT, GROUP, SERVICE...). Antes, só o fluxo direct checava
		// bloqueio; criar um "grupo" com kind=GROUP furava bloqueio e proteção de menores (EN-05/E-16).
		if (participantIds == null || participantIds.isEmpty()) {
			throw new com.socialcoisa.rede.shared.web.ApiException(400, "INVALID_PARTICIPANTS",
				"Informe pelo menos um participante.");
		}
		java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
		for (String raw : participantIds) {
			if (org.springframework.util.StringUtils.hasText(raw)) {
				unique.add(raw.trim());
			}
		}
		if (unique.isEmpty()) {
			throw new com.socialcoisa.rede.shared.web.ApiException(400, "INVALID_PARTICIPANTS",
				"Informe pelo menos um participante.");
		}
		if (unique.size() > 100) {
			throw new com.socialcoisa.rede.shared.web.ApiException(400, "TOO_MANY_PARTICIPANTS",
				"Uma conversa pode ter no maximo 100 participantes.");
		}
		// JOB and SERVICE conversations are opened by their own flows (and exempt from the minor
		// rule below); the generic endpoint only creates person-to-person conversations.
		String normalizedKind = kind == null || kind.isBlank() ? "DIRECT" : kind.trim().toUpperCase(java.util.Locale.ROOT);
		if (!"DIRECT".equals(normalizedKind) && !"GROUP".equals(normalizedKind)) {
			throw new ApiException(400, "INVALID_CONVERSATION_KIND", "Tipo de conversa invalido.");
		}
		ageGate.requireCanWrite(createdBy);
		unique.add(createdBy);
		// One read per kind for the whole group (up to 100 people), never one per participant.
		// hiddenIdsFor covers blocks in both directions.
		var creatorHidden = blockedUsers.hiddenIdsFor(createdBy);
		java.util.List<String> others = unique.stream().filter(pid -> !pid.equals(createdBy)).toList();
		if (others.stream().anyMatch(creatorHidden::contains)) {
			throw new com.socialcoisa.rede.shared.web.ApiException(403, "USER_BLOCKED",
				"Nao e possivel conversar com este usuario.");
		}
		var minors = agePolicy.minorIds(others);
		if (!minors.isEmpty()) {
			if (!connections.findAcceptedAmong(createdBy, minors).containsAll(minors)) {
				throw minorDmRestricted();
			}
		}
		String id = "conversation_" + UUID.randomUUID().toString().replace("-", "");
		Conversation conversation = Conversation.create(id, normalizedKind, title, createdBy, clock.instant());
		repository.saveConversation(conversation);
		for (String pid : unique) {
			repository.saveParticipant(ConversationParticipant.create(id, pid, clock.instant()));
		}
		return conversation;
	}

	/**
	 * Conversa direta entre dois usuarios: devolve a existente ou cria (com os dois como
	 * participantes). Usada, por exemplo, para entregar uma candidatura na DM de quem publicou.
	 */
	@Transactional
	public String directConversationBetween(String userA, String userB) {
		return directConversationBetween(userA, userB, "DIRECT");
	}

	/**
	 * Conversa direta marcada com a origem. A candidatura de vaga usa kind = "JOB" para o inbox
	 * poder separar as conversas de vaga das conversas com pessoas.
	 */
	@Transactional
	public String directConversationBetween(String userA, String userB, String kind) {
		var existing = repository.findDirectBetween(userA, userB);
		if (existing.isPresent()) {
			return existing.get().id();
		}
		String id = "conversation_" + java.util.UUID.randomUUID().toString().replace("-", "");
		repository.saveConversation(Conversation.create(id, kind, null, userA, clock.instant()));
		repository.saveParticipant(ConversationParticipant.create(id, userA, clock.instant()));
		repository.saveParticipant(ConversationParticipant.create(id, userB, clock.instant()));
		return id;
	}

	/**
	 * Conversa de SERVICO entre duas pessoas: a que o botao do card de servico abre.
	 *
	 * <p>Ela e separada da conversa direta porque tem ASSUNTO (o tipo do servico e o pedido da
	 * contratacao) e porque o prestador precisa de uma aba propria na caixa de entrada — a conversa
	 * direta nao diz do que se trata. Clicar duas vezes no botao nao abre duas conversas: a
	 * existente e reaproveitada.
	 */
	@Transactional
	public String serviceConversationBetween(String userA, String userB, String assunto) {
		var existing = repository.findServiceBetween(userA, userB);
		if (existing.isPresent()) {
			return existing.get().id();
		}
		String id = "conversation_" + java.util.UUID.randomUUID().toString().replace("-", "");
		repository.saveConversation(Conversation.create(id, "SERVICE", assunto, userA, clock.instant()));
		repository.saveParticipant(ConversationParticipant.create(id, userA, clock.instant()));
		repository.saveParticipant(ConversationParticipant.create(id, userB, clock.instant()));
		return id;
	}

	@Transactional
	public Message sendMessage(String conversationId, String senderId, String text) {
		return sendMessage(conversationId, senderId, text, java.util.List.of());
	}

	@Transactional
	public Message sendMessage(String conversationId, String senderId, String text,
			java.util.List<String> mediaIds) {
		return sendMessage(conversationId, senderId, text, mediaIds, false);
	}

	@Transactional
	public Message sendMessage(String conversationId, String senderId, String text,
			java.util.List<String> mediaIds, boolean mediaSpoiler) {
		return sendMessage(conversationId, senderId, text, mediaIds, mediaSpoiler, null);
	}

	@Transactional
	public Message sendMessage(String conversationId, String senderId, String text,
			java.util.List<String> mediaIds, boolean mediaSpoiler, String replyToId) {
		// ECA Digital: sem data de nascimento declarada, nao envia mensagem (tela de regularizacao).
		ageGate.requireCanWrite(senderId);
		// Sancao de moderacao: conta restrita nao manda mensagem. O canal privado e onde a
		// moderacao nao enxerga, entao deixar a conversa aberta era a forma mais facil de a
		// restricao ser contornada.
		sanctionGate.requireNotRestricted(senderId);
		userRateLimiter.require(senderId, UserRateLimiter.SEND_MESSAGE);
		behavioralTimingGuard.record(senderId);
		Conversation conversation = repository.findConversationById(conversationId)
			.orElseThrow(() -> new ApiException(404, "CONVERSATION_NOT_FOUND", "Conversa nao encontrada."));
		if (!repository.isParticipant(conversationId, senderId)) {
			throw new ApiException(403, "NOT_PARTICIPANT", "Voce nao participa desta conversa.");
		}
		protegeMenorDestinatario(conversationId, senderId);
		java.util.List<String> midias = mediaIds == null ? java.util.List.of()
			: mediaIds.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList();
		String corpo = text == null ? "" : text.trim();
		if (corpo.isEmpty() && midias.isEmpty()) {
			throw new ApiException(400, "EMPTY_MESSAGE", "Escreva uma mensagem ou anexe uma midia.");
		}
		if (midias.size() > 4) {
			throw new ApiException(400, "TOO_MANY_MEDIA", "Anexe no maximo 4 midias por mensagem.");
		}
		// Reply: a mensagem citada precisa existir e pertencer a esta conversa.
		if (replyToId != null) {
			repository.findMessageById(replyToId)
				.filter(m -> m.conversationId().equals(conversationId))
				.orElseThrow(() -> new ApiException(400, "INVALID_REPLY", "Mensagem citada nao encontrada nesta conversa."));
		}
		// Duplo clique/retry: a MESMA mensagem (texto + mídias) do mesmo remetente em até 2
		// minutos devolve a linha já criada em vez de duplicar e notificar de novo.
		var duplicate = repository.findRecentDuplicate(conversationId, senderId, corpo,
			clock.instant().minusSeconds(10));
		if (duplicate.isPresent() && duplicate.get().mediaIds().equals(midias)
				&& duplicate.get().mediaSpoiler() == mediaSpoiler) {
			return duplicate.get();
		}
		String id = "message_" + UUID.randomUUID().toString().replace("-", "");
		Message message = Message.create(id, conversationId, senderId, corpo, midias, mediaSpoiler, replyToId, clock.instant());
		repository.saveMessage(message);
		// Ao enviar, a conversa fica lida para QUEM ENVIOU (PR #15) — antes o contador continuava
		// aceso mesmo com a conversa aberta.
		repository.markRead(conversationId, senderId, clock.instant());
		// A lista de conversas ordena por updatedAt; sem este toque a conversa antiga fica no lugar
		// mesmo recebendo mensagem nova (#25 dos issues).
		repository.saveConversation(conversation.touch(clock.instant()));
		String preview = corpo.isEmpty()
			? (midias.isEmpty() ? "Nova mensagem" : "Enviou uma midia")
			: (corpo.length() > 80 ? corpo.substring(0, 77) + "..." : corpo);
		List<ConversationParticipant> participants = repository.findParticipantsByConversation(conversationId);
		for (ConversationParticipant p : participants) {
			if (p.userId().equals(senderId)) continue;
			notificationService.create(p.userId(), "NEW_MESSAGE", senderId,
				"Nova mensagem", preview,
				"/api/conversations/" + conversationId, "Responder");
		}
		return message;
	}

	/** Envia mensagem E2E: o texto já é ciphertext, o server não lê. */
	@Transactional
	public Message sendE2eMessage(String conversationId, String senderId, String ciphertext,
			String iv, java.util.List<String> mediaIds, boolean mediaSpoiler) {
		return sendE2eMessage(conversationId, senderId, ciphertext, iv, mediaIds, mediaSpoiler, null);
	}

	@Transactional
	public Message sendE2eMessage(String conversationId, String senderId, String ciphertext,
			String iv, java.util.List<String> mediaIds, boolean mediaSpoiler, String replyToId) {
		ageGate.requireCanWrite(senderId);
		sanctionGate.requireNotRestricted(senderId);
		userRateLimiter.require(senderId, UserRateLimiter.SEND_MESSAGE);
		behavioralTimingGuard.record(senderId);
		Conversation conversation = repository.findConversationById(conversationId)
			.orElseThrow(() -> new ApiException(404, "CONVERSATION_NOT_FOUND", "Conversa nao encontrada."));
		if (!repository.isParticipant(conversationId, senderId)) {
			throw new ApiException(403, "NOT_PARTICIPANT", "Voce nao participa desta conversa.");
		}
		protegeMenorDestinatario(conversationId, senderId);
		if (ciphertext == null || ciphertext.isBlank()) {
			throw new ApiException(400, "EMPTY_MESSAGE", "Mensagem cifrada vazia.");
		}
		if (ciphertext.length() > 8000) {
			throw new ApiException(400, "MESSAGE_TOO_LONG", "Mensagem excede o tamanho maximo.");
		}
		if (iv == null || iv.isBlank() || iv.length() > 44) {
			throw new ApiException(400, "MISSING_IV", "IV obrigatorio para mensagem E2E.");
		}
		if (replyToId != null) {
			repository.findMessageById(replyToId)
				.filter(m -> m.conversationId().equals(conversationId))
				.orElseThrow(() -> new ApiException(400, "INVALID_REPLY", "Mensagem citada nao encontrada nesta conversa."));
		}
		java.util.List<String> midias = mediaIds == null ? java.util.List.of()
			: mediaIds.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList();
		String id = "message_" + UUID.randomUUID().toString().replace("-", "");
		Message message = Message.createE2e(id, conversationId, senderId, ciphertext, iv, midias, mediaSpoiler,
			replyToId, clock.instant());
		repository.saveMessage(message);
		repository.markRead(conversationId, senderId, clock.instant());
		repository.saveConversation(conversation.touch(clock.instant()));
		List<ConversationParticipant> participants = repository.findParticipantsByConversation(conversationId);
		for (ConversationParticipant p : participants) {
			if (p.userId().equals(senderId)) continue;
			notificationService.create(p.userId(), "NEW_MESSAGE", senderId,
				"Nova mensagem", "Mensagem cifrada",
				"/api/conversations/" + conversationId, "Responder");
		}
		return message;
	}

	/**
	 * ECA Digital: destinatario menor de 18 so recebe mensagem de quem tem conexao ACEITA com ele.
	 * Desconhecidos nao abrem conversa com menor — o primeiro vetor de contato abusivo fica fechado.
	 */
	private void protegeMenorDestinatario(String conversationId, String senderId) {
		// Applies to every person-to-person conversation, groups included: a minor added to a group
		// did not choose its other members. JOB and SERVICE conversations are opened by the minor's
		// own action (applying, hiring) and keep working.
		String kind = repository.findConversationById(conversationId)
			.map(conversation -> conversation.kind())
			.orElse("DIRECT");
		if ("JOB".equalsIgnoreCase(kind) || "SERVICE".equalsIgnoreCase(kind)) {
			return;
		}
		List<com.socialcoisa.rede.messaging.domain.ConversationParticipant> participants = repository
			.findParticipantsByConversation(conversationId);
		for (com.socialcoisa.rede.messaging.domain.ConversationParticipant participant : participants) {
			String recipientId = participant.userId();
			if (recipientId.equals(senderId)) {
				continue;
			}
			requireMayContactMinor(senderId, recipientId);
		}
	}

	/** ECA Digital: a minor is only reachable by someone with an ACCEPTED connection to them. */
	private void requireMayContactMinor(String actorId, String recipientId) {
		if (agePolicy.isMinor(recipientId) && !connections.existsAcceptedConnection(actorId, recipientId)) {
			throw minorDmRestricted();
		}
	}

	private static ApiException minorDmRestricted() {
		return new ApiException(403, "MINOR_DM_RESTRICTED",
			"Para proteger usuarios menores de idade, so e possivel conversar com eles apos uma conexao aceita.");
	}



	@Transactional
	public void markRead(String conversationId, String userId) {
		if (!repository.isParticipant(conversationId, userId)) {
			throw new ApiException(403, "NOT_PARTICIPANT", "Voce nao participa desta conversa.");
		}
		repository.markRead(conversationId, userId, clock.instant());
	}

	public List<Conversation> listConversations(String userId, java.time.Instant before, int limit) {
		return repository.findConversationsByUserId(userId, before, limit);
	}

	public int hideConversations(String userId, java.util.List<String> ids) {
		return repository.hideConversations(userId, ids, clock.instant());
	}

	/** As conversas arquivadas (apagadas so para quem chama). */
	public List<Conversation> listArchivedConversations(String userId, java.time.Instant before, int limit) {
		return repository.findArchivedConversationsByUserId(userId, before, limit);
	}

	/** Restaurar: a conversa volta para a caixa de entrada de quem chamou. */
	public int unhideConversations(String userId, java.util.List<String> ids) {
		return repository.unhideConversations(userId, ids);
	}

	public boolean isParticipant(String conversationId, String userId) {
		return repository.isParticipant(conversationId, userId);
	}

	/**
	 * A mensagem existe e e mesmo desta conversa.
	 *
	 * O caminho da reacao recebe o id da conversa e o id da mensagem separados: sem esta conferencia,
	 * o id da conversa era decorativo e dava para reagir (e ler reacoes) em mensagem de conversa alheia.
	 */
	public boolean messageBelongsTo(String messageId, String conversationId) {
		return repository.findMessageById(messageId)
			.map(message -> message.conversationId().equals(conversationId))
			.orElse(false);
	}

	public Conversation getConversation(String id) {
		return repository.findConversationById(id)
			.orElseThrow(() -> new ApiException(404, "CONVERSATION_NOT_FOUND", "Conversa nao encontrada."));
	}

	public List<ConversationParticipant> getParticipants(String conversationId) {
		return repository.findParticipantsByConversation(conversationId);
	}

	public int countUnread(String conversationId, String userId) {
		return repository.countUnread(conversationId, userId);
	}

	public int countTotalUnread(String userId) {
		return repository.countTotalUnread(userId);
	}

	public List<Message> getMessages(String conversationId, String viewerId, java.time.Instant before, int limit) {
		if (!repository.findConversationById(conversationId).isPresent()) {
			throw new ApiException(404, "CONVERSATION_NOT_FOUND", "Conversa nao encontrada.");
		}
		// O leitor entra na consulta: a mensagem que ELE apagou nao volta (para o outro continua).
		return repository.findMessagesByConversation(conversationId, viewerId, before, limit);
	}

	/** "Apagar para mim" por mensagem: some so para quem apagou. */
	@Transactional
	public int hideMessages(String conversationId, String userId, java.util.List<String> messageIds) {
		if (!repository.isParticipant(conversationId, userId)) {
			throw new ApiException(403, "NOT_PARTICIPANT", "Voce nao participa desta conversa.");
		}
		List<String> ids = messageIds == null ? List.of() : messageIds.stream()
			.filter(id -> id != null && !id.isBlank()).map(String::trim).distinct().toList();
		if (ids.isEmpty()) {
			throw new ApiException(400, "EMPTY_MESSAGE_LIST", "Selecione ao menos uma mensagem.");
		}
		if (repository.countMessagesInConversation(conversationId, ids) != ids.size()) {
			throw new ApiException(404, "MESSAGE_NOT_FOUND", "Mensagem nao encontrada nesta conversa.");
		}
		return repository.hideMessages(userId, ids, clock.instant());
	}

	public record LastMessagePreview(String text, boolean e2e, String iv, java.time.Instant time) {
		static final LastMessagePreview EMPTY = new LastMessagePreview(null, false, null, null);
	}

	public LastMessagePreview getLastMessagePreview(String conversationId) {
		return repository.findLastMessage(conversationId)
			.map(m -> new LastMessagePreview(m.text(), m.e2e(), m.iv(), m.createdAt()))
			.orElse(LastMessagePreview.EMPTY);
	}

	@Transactional
	public boolean addReaction(String messageId, String userId, ReactionType type) {
		String id = "mreac_" + UUID.randomUUID().toString().replace("-", "");
		return repository.addMessageReaction(id, messageId, userId, type, clock.instant());
	}

	public List<MessagingRepository.ReactionSummary> getReactions(String messageId) {
		return repository.findReactionsByMessageId(messageId);
	}

	/** Mensagens por ids em batch (reply previews). */
	public List<Message> findMessagesByIds(java.util.Collection<String> messageIds) {
		return repository.findMessagesByIds(messageIds);
	}

	/**
	 * Resumo das reações de VÁRIAS mensagens (contagem por tipo + a reação de quem está vendo),
	 * em uma consulta só — o histórico não faz N+1 para desenhar os emojis.
	 */
	public java.util.Map<String, MessageReactionSummary> reactionSummaries(
			java.util.Collection<String> messageIds, String viewerId) {
		java.util.Map<String, java.util.Map<String, Integer>> counts = new java.util.LinkedHashMap<>();
		java.util.Map<String, String> mine = new java.util.HashMap<>();
		for (var row : repository.findReactionsByMessageIds(messageIds)) {
			counts.computeIfAbsent(row.messageId(), key -> new java.util.LinkedHashMap<>())
				.merge(row.type().name(), 1, Integer::sum);
			if (viewerId != null && viewerId.equals(row.userId())) {
				mine.put(row.messageId(), row.type().name());
			}
		}
		java.util.Map<String, MessageReactionSummary> out = new java.util.HashMap<>();
		counts.forEach((messageId, byType) -> out.put(messageId,
			new MessageReactionSummary(byType, mine.get(messageId))));
		return out;
	}

	public record MessageReactionSummary(java.util.Map<String, Integer> counts, String myReaction) {
	}

}
