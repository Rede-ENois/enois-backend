package com.socialcoisa.rede.messaging.application;

import java.text.Normalizer;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.messaging.application.port.out.ConversationTopicRepository;
import com.socialcoisa.rede.messaging.application.port.out.MessagingRepository;
import com.socialcoisa.rede.messaging.domain.Conversation;
import com.socialcoisa.rede.messaging.domain.ConversationParticipant;
import com.socialcoisa.rede.messaging.domain.ConversationTopic;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Topicos do grupo de conversa (kind=GROUP): organizam as mensagens por assunto.
 *
 * <p>A regra de quem manda no grupo e a mesma da gestao de participantes: o dono e quem criou a
 * conversa, e o administrador e o papel gravado na linha do participante. Qualquer participante le
 * os topicos; dono ou administrador criam, renomeiam, arquivam e reordenam.</p>
 *
 * <p>A mensagem que chega com topico passa por aqui antes de ser gravada: o topico precisa ser
 * daquela conversa e estar aberto. O id existir nao basta — um topico de outro grupo nao pode
 * organizar esta conversa.</p>
 */
@Service
public class ConversationTopicService {

	private static final int TOPIC_NAME_MAX = 60;
	private static final int MAX_TOPICS = 30;

	private static final String GROUP_KIND = "GROUP";

	private final ConversationTopicRepository topics;
	private final MessagingRepository conversations;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	public ConversationTopicService(ConversationTopicRepository topics, MessagingRepository conversations,
			ApplicationEventPublisher events, Clock clock) {
		this.topics = topics;
		this.conversations = conversations;
		this.events = events;
		this.clock = clock;
	}

	/** Topicos da conversa, na ordem do grupo. Qualquer participante le. */
	@Transactional(readOnly = true)
	public List<ConversationTopic> listTopics(String conversationId, String actorId) {
		requireGroup(conversationId);
		requireParticipant(conversationId, actorId);
		return topics.findTopicsByConversation(conversationId);
	}

	@Transactional
	public ConversationTopic createTopic(String conversationId, String actorId, String rawName) {
		Conversation conversation = requireGroup(conversationId);
		requireManager(conversation, requireParticipant(conversationId, actorId));
		String name = requireName(rawName);
		if (topics.countTopics(conversationId) >= MAX_TOPICS) {
			throw new ApiException(400, "TOO_MANY_TOPICS", "Um grupo pode ter no maximo 30 topicos.");
		}
		if (topics.topicNameExists(conversationId, name)) {
			throw new ApiException(409, "TOPIC_EXISTS", "Ja existe um topico com esse nome.");
		}
		ConversationTopic topic = ConversationTopic.create("ctopic_" + UUID.randomUUID().toString().replace("-", ""),
			conversationId, name, topics.findTopicsByConversation(conversationId).size(), clock.instant());
		topics.saveTopic(topic);
		publishChange(conversationId);
		return topic;
	}

	/** Nome nulo mantem o atual; repetir o proprio nome (em qualquer caixa) e permitido. */
	@Transactional
	public ConversationTopic renameTopic(String conversationId, String actorId, String topicId, String rawName) {
		Conversation conversation = requireGroup(conversationId);
		requireManager(conversation, requireParticipant(conversationId, actorId));
		ConversationTopic topic = requireTopic(conversationId, topicId);
		String name = rawName == null ? null : requireName(rawName);
		if (name != null && !name.equalsIgnoreCase(topic.name()) && topics.topicNameExists(conversationId, name)) {
			throw new ApiException(409, "TOPIC_EXISTS", "Ja existe um topico com esse nome.");
		}
		ConversationTopic renamed = topic.rename(name, clock.instant());
		topics.saveTopic(renamed);
		publishChange(conversationId);
		return renamed;
	}

	/** Arquivar para de receber mensagem nova; o que ja esta no topico continua legivel. */
	@Transactional
	public ConversationTopic archiveTopic(String conversationId, String actorId, String topicId) {
		Conversation conversation = requireGroup(conversationId);
		requireManager(conversation, requireParticipant(conversationId, actorId));
		ConversationTopic archived = requireTopic(conversationId, topicId).archive(clock.instant());
		topics.saveTopic(archived);
		publishChange(conversationId);
		return archived;
	}

	/** A ordem enviada precisa conter TODOS os topicos da conversa, uma vez cada. */
	@Transactional
	public List<ConversationTopic> reorderTopics(String conversationId, String actorId, List<String> orderedTopicIds) {
		Conversation conversation = requireGroup(conversationId);
		requireManager(conversation, requireParticipant(conversationId, actorId));
		List<ConversationTopic> current = topics.findTopicsByConversation(conversationId);
		Set<String> sent = orderedTopicIds == null ? Set.of() : new HashSet<>(orderedTopicIds);
		Set<String> existing = new HashSet<>(current.stream().map(ConversationTopic::id).toList());
		if (orderedTopicIds == null || sent.size() != orderedTopicIds.size() || !sent.equals(existing)) {
			throw new ApiException(400, "INVALID_ORDER", "A ordem precisa listar todos os topicos do grupo.");
		}
		var now = clock.instant();
		for (int index = 0; index < orderedTopicIds.size(); index++) {
			String topicId = orderedTopicIds.get(index);
			ConversationTopic topic = current.stream().filter(item -> item.id().equals(topicId)).findFirst()
				.orElseThrow(() -> new ApiException(400, "INVALID_ORDER", "Topico desconhecido na ordem."));
			topics.saveTopic(topic.withPosition(index, now));
		}
		return topics.findTopicsByConversation(conversationId);
	}

	/**
	 * Confere o topico de uma mensagem nova: precisa ser da conversa e estar aberto. Devolve o id
	 * limpo, ou {@code null} quando a mensagem vem sem topico.
	 */
	public String requireUsableTopic(String conversationId, String rawTopicId) {
		if (rawTopicId == null || rawTopicId.isBlank()) {
			return null;
		}
		String topicId = rawTopicId.trim();
		ConversationTopic topic = requireTopic(conversationId, topicId);
		if (!topic.acceptsMessages()) {
			throw new ApiException(409, "TOPIC_ARCHIVED", "Este topico esta arquivado.");
		}
		return topic.id();
	}

	// ------------------------------------------------------------------ guardas

	private Conversation requireGroup(String conversationId) {
		Conversation conversation = conversations.findConversationById(conversationId)
			.orElseThrow(() -> new ApiException(404, "CONVERSATION_NOT_FOUND", "Conversa nao encontrada."));
		if (!GROUP_KIND.equalsIgnoreCase(conversation.kind())) {
			throw new ApiException(400, "INVALID_CONVERSATION_KIND", "Esta conversa nao e um grupo.");
		}
		return conversation;
	}

	private ConversationParticipant requireParticipant(String conversationId, String userId) {
		return conversations.findParticipant(conversationId, userId)
			.orElseThrow(() -> new ApiException(403, "NOT_PARTICIPANT", "Voce nao participa desta conversa."));
	}

	/** Mesma derivacao da gestao de participantes: o dono e o criador; o resto vem do papel. */
	private void requireManager(Conversation conversation, ConversationParticipant actor) {
		boolean owner = conversation.createdBy().equals(actor.userId());
		if (!owner && !actor.role().manages()) {
			throw new ApiException(403, "GROUP_ADMIN_REQUIRED",
				"Somente o dono ou um administrador pode fazer isso.");
		}
	}

	private ConversationTopic requireTopic(String conversationId, String topicId) {
		return topics.findTopic(conversationId, topicId)
			.orElseThrow(() -> new ApiException(404, "TOPIC_NOT_FOUND", "Topico nao encontrado neste grupo."));
	}

	/**
	 * Nome com espacos colapsados e sem caractere de controle. A comparacao de duplicata e a da
	 * coluna unica (collation sem acento), entao "Sexta" e "Sextá" contam como o mesmo topico.
	 */
	private static String requireName(String rawName) {
		if (!StringUtils.hasText(rawName)) {
			throw new ApiException(400, "INVALID_TOPIC_NAME", "Nome do topico e obrigatorio.");
		}
		String name = Normalizer.normalize(rawName.trim().replaceAll("\\s+", " "), Normalizer.Form.NFC);
		if (name.length() > TOPIC_NAME_MAX) {
			throw new ApiException(400, "INVALID_TOPIC_NAME",
				"Nome do topico deve ter ate " + TOPIC_NAME_MAX + " caracteres.");
		}
		if (name.chars().anyMatch(Character::isISOControl)) {
			throw new ApiException(400, "INVALID_TOPIC_NAME", "Nome do topico contem caracteres invalidos.");
		}
		return name;
	}

	private void publishChange(String conversationId) {
		events.publishEvent(new ConversationChanged(conversationId,
			conversations.findParticipantsByConversation(conversationId).stream()
				.map(ConversationParticipant::userId)
				.toList()));
	}

}
