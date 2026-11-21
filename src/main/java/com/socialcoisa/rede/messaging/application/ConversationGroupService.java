package com.socialcoisa.rede.messaging.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.e2e.application.port.out.E2eKeyRepository;
import com.socialcoisa.rede.messaging.application.port.out.MessagingRepository;
import com.socialcoisa.rede.messaging.domain.Conversation;
import com.socialcoisa.rede.messaging.domain.ConversationParticipant;
import com.socialcoisa.rede.messaging.domain.ConversationRole;
import com.socialcoisa.rede.messaging.domain.Message;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.shared.web.ApiException;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Gestao dos participantes de um GRUPO DE CONVERSA (kind=GROUP) — o grupo do chat de mensagens.
 *
 * <p>Este grupo nao e o modulo {@code groups}: la o grupo tem entrada por solicitacao e conteudo
 * proprio; aqui o grupo e a conversa de chat com mais de duas pessoas, e o que faltava era o
 * painel de admin (issue #915): quem entra, quem sai e quem manda.</p>
 *
 * <p><b>Dono e administrador.</b> O dono e quem criou a conversa ({@code conversations.created_by}),
 * e nao um papel gravado na linha do participante: um grupo nasce com o criador, e dois lugares
 * guardando o dono podiam discordar. O dono promove e remove administradores; o administrador
 * adiciona e remove gente comum, mas nao mexe em outro administrador nem no dono. O dono nao sai
 * do proprio grupo: nao ha transferencia de dono, e um grupo sem dono ficaria sem quem promova
 * ninguem.</p>
 *
 * <p><b>Quem entra depois ve o historico inteiro.</b> A decisao e do produto: a pessoa que entra
 * depois nao ve so as mensagens novas, ve tudo o que foi dito antes dela chegar. O servidor nao
 * pode fazer isso sozinho: a chave de cada mensagem e gerada por quem enviou e chega cifrada para
 * cada participante, e o servidor so guarda essa copia cifrada — quem tem a chave em claro e o
 * aparelho de quem ja estava no grupo. Por isso a concessao de historico ({@link
 * #grantHistoryKeys}) recebe de quem administra o grupo a chave de cada mensagem antiga, ja
 * cifrada para a chave publica da pessoa que entrou, e o servidor acrescenta essa chave ao mapa
 * da mensagem. O cliente faz a parte que e dele (abrir a chave com a chave privada de quem
 * administra e fechá-la com a chave publica de quem entrou).</p>
 */
@Service
public class ConversationGroupService {

	/** Mesmo teto do envio de mensagem: uma conversa nao passa de 100 participantes. */
	private static final int MAX_PARTICIPANTS = 100;

	/** Quantas mensagens de historico uma concessao traz de uma vez. */
	private static final int MAX_HISTORY_KEYS = 200;

	/** Mesmo teto do envio: o mapa de chaves da mensagem e TEXT e nao cresce sem limite. */
	private static final int MAX_ENCRYPTED_KEYS_LENGTH = 16000;

	private static final String GROUP_KIND = "GROUP";

	private static final int MAX_ID_LENGTH = 64;
	private static final java.util.regex.Pattern ID_FORMAT = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

	private final MessagingRepository repository;
	private final ConversationReachPolicy reach;
	private final NotificationService notifications;
	private final E2eKeyRepository e2eKeys;
	private final ApplicationEventPublisher events;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public ConversationGroupService(MessagingRepository repository, ConversationReachPolicy reach,
			NotificationService notifications, E2eKeyRepository e2eKeys, ApplicationEventPublisher events,
			ObjectMapper objectMapper, Clock clock) {
		this.repository = repository;
		this.reach = reach;
		this.notifications = notifications;
		this.e2eKeys = e2eKeys;
		this.events = events;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	/** Quem participa do grupo, com o papel de cada um. */
	@Transactional(readOnly = true)
	public List<GroupParticipant> listParticipants(String conversationId, String actorId) {
		Conversation conversation = requireGroup(conversationId);
		requireParticipant(conversationId, actorId);
		return repository.findParticipantsByConversation(conversationId).stream()
			.map(participant -> new GroupParticipant(participant.userId(), roleOf(conversation, participant),
				participant.joinedAt()))
			.toList();
	}

	/**
	 * Convida alguem para o grupo. So o dono e os administradores podem convidar, e quem entra
	 * passa pelas mesmas regras de alcance da criacao da conversa (a conta existe, nao esta
	 * banida, nao esta bloqueada por quem administra, e menor precisa de conexao aceita).
	 */
	@Transactional
	public GroupParticipant addParticipant(String conversationId, String actorId, String rawTargetId) {
		Conversation conversation = requireGroup(conversationId);
		ConversationParticipant actor = requireParticipant(conversationId, actorId);
		requireManager(conversation, actor);
		String targetId = requireTargetId(rawTargetId);
		if (repository.findParticipant(conversationId, targetId).isPresent()) {
			throw new ApiException(409, "ALREADY_PARTICIPANT", "Esta conta ja participa do grupo.");
		}
		reach.resolveRecipient(GROUP_KIND, actorId, targetId);
		List<ConversationParticipant> participants = repository.findParticipantsByConversation(conversationId);
		if (participants.size() >= MAX_PARTICIPANTS) {
			throw new ApiException(400, "TOO_MANY_PARTICIPANTS",
				"Uma conversa pode ter no maximo 100 participantes.");
		}
		ConversationParticipant added = ConversationParticipant.create(conversationId, targetId, clock.instant());
		repository.saveParticipant(added);
		notifications.create(targetId, "CHAT_GROUP_MEMBER_ADDED", actorId, "Voce entrou no grupo",
			groupTitle(conversation), MessagingService.conversationHref(conversationId), "Abrir");
		publishChange(conversationId, withParticipant(participants, added));
		return new GroupParticipant(targetId, ConversationRole.MEMBER, added.joinedAt());
	}

	/**
	 * Tira alguem do grupo. A conversa sai da caixa de quem foi removido e a pessoa perde o acesso
	 * as mensagens; a conversa em si continua existindo para quem ficou.
	 */
	@Transactional
	public void removeParticipant(String conversationId, String actorId, String rawTargetId) {
		Conversation conversation = requireGroup(conversationId);
		ConversationParticipant actor = requireParticipant(conversationId, actorId);
		requireManager(conversation, actor);
		String targetId = requireTargetId(rawTargetId);
		ConversationParticipant target = repository.findParticipant(conversationId, targetId)
			.orElseThrow(() -> new ApiException(404, "PARTICIPANT_NOT_FOUND",
				"Esta conta nao participa do grupo."));
		if (conversation.createdBy().equals(target.userId())) {
			throw new ApiException(403, "GROUP_OWNER_PROTECTED", "O dono do grupo nao pode ser removido.");
		}
		if (roleOf(conversation, actor) == ConversationRole.ADMIN
				&& roleOf(conversation, target) == ConversationRole.ADMIN) {
			throw new ApiException(403, "GROUP_ADMIN_PROTECTED",
				"Somente o dono pode remover outro administrador.");
		}
		repository.deleteParticipant(conversationId, target.userId());
		// A chave da conversa guardada no servidor deixa de valer para quem nao participa mais.
		e2eKeys.deleteConversationKey(conversationId, target.userId());
		notifications.create(target.userId(), "CHAT_GROUP_MEMBER_REMOVED", actorId,
			"Voce foi removido do grupo",
			groupTitle(conversation), MessagingService.conversationHref(conversationId), "Abrir");
		publishChange(conversationId, repository.findParticipantsByConversation(conversationId));
	}

	/** Promove a administrador (ou volta a membro). Quem muda papel e sempre o dono. */
	@Transactional
	public GroupParticipant changeRole(String conversationId, String actorId, String rawTargetId, String rawRole) {
		Conversation conversation = requireGroup(conversationId);
		ConversationParticipant actor = requireParticipant(conversationId, actorId);
		if (roleOf(conversation, actor) != ConversationRole.OWNER) {
			throw new ApiException(403, "GROUP_OWNER_REQUIRED", "Somente o dono do grupo pode mudar papeis.");
		}
		String targetId = requireTargetId(rawTargetId);
		ConversationParticipant target = repository.findParticipant(conversationId, targetId)
			.orElseThrow(() -> new ApiException(404, "PARTICIPANT_NOT_FOUND",
				"Esta conta nao participa do grupo."));
		if (conversation.createdBy().equals(target.userId())) {
			throw new ApiException(403, "GROUP_OWNER_PROTECTED", "O dono do grupo nao pode perder o papel.");
		}
		ConversationRole role = parseRole(rawRole);
		repository.updateParticipantRole(conversationId, target.userId(), role);
		publishChange(conversationId, repository.findParticipantsByConversation(conversationId));
		return new GroupParticipant(target.userId(), role, target.joinedAt());
	}

	/**
	 * Concessao de historico: acrescenta, no mapa de chaves de cada mensagem, a chave cifrada para
	 * a pessoa que entrou depois no grupo. E o que faz quem entra depois ver o historico inteiro.
	 *
	 * <p>A chave chega pronta: quem administra abre a chave da mensagem com a propria chave privada
	 * e fecha a chave da pessoa nova com a chave publica dela. O servidor nao abre nem fecha nada —
	 * ele so guarda, e nao pode ler.</p>
	 */
	@Transactional
	public int grantHistoryKeys(String conversationId, String actorId, String rawTargetId,
			Map<String, String> keys) {
		Conversation conversation = requireGroup(conversationId);
		ConversationParticipant actor = requireParticipant(conversationId, actorId);
		requireManager(conversation, actor);
		String targetId = requireTargetId(rawTargetId);
		ConversationParticipant target = repository.findParticipant(conversationId, targetId)
			.orElseThrow(() -> new ApiException(404, "PARTICIPANT_NOT_FOUND",
				"Esta conta nao participa do grupo."));
		if (keys == null || keys.isEmpty()) {
			throw new ApiException(400, "EMPTY_HISTORY_KEYS", "Informe as chaves do historico.");
		}
		if (keys.size() > MAX_HISTORY_KEYS) {
			throw new ApiException(400, "TOO_MANY_HISTORY_KEYS",
				"A concessao traz no maximo 200 mensagens por vez.");
		}
		int granted = 0;
		for (Map.Entry<String, String> entry : keys.entrySet()) {
			String messageId = entry.getKey() == null ? "" : entry.getKey().trim();
			String wrappedKey = entry.getValue();
			if (messageId.isEmpty() || wrappedKey == null || wrappedKey.isBlank()) {
				throw new ApiException(400, "INVALID_HISTORY_KEY",
					"Cada chave do historico precisa da mensagem e da chave cifrada.");
			}
			Message message = repository.findMessageById(messageId)
				.filter(candidate -> conversationId.equals(candidate.conversationId()))
				.orElseThrow(() -> new ApiException(404, "MESSAGE_NOT_FOUND",
					"Mensagem nao encontrada nesta conversa."));
			if (!message.e2e()) {
				throw new ApiException(409, "MESSAGE_NOT_ENCRYPTED", "Esta mensagem nao foi cifrada.");
			}
			Map<String, String> current = readKeys(message.encryptedKeys());
			if (current.containsKey(target.userId())) {
				continue;
			}
			current.put(target.userId(), wrappedKey);
			String merged = writeKeys(current);
			if (merged.length() > MAX_ENCRYPTED_KEYS_LENGTH) {
				throw new ApiException(400, "ENCRYPTED_KEYS_TOO_LONG",
					"O mapa de chaves da mensagem passou do limite.");
			}
			repository.updateMessageEncryptedKeys(messageId, merged);
			granted++;
		}
		return granted;
	}

	// ------------------------------------------------------------------ guardas

	/** A conversa existe e e um grupo de chat (nao uma DM, nao uma conversa de vaga). */
	private Conversation requireGroup(String conversationId) {
		Conversation conversation = repository.findConversationById(conversationId)
			.orElseThrow(() -> new ApiException(404, "CONVERSATION_NOT_FOUND", "Conversa nao encontrada."));
		if (!GROUP_KIND.equalsIgnoreCase(conversation.kind())) {
			throw new ApiException(400, "INVALID_CONVERSATION_KIND", "Esta conversa nao e um grupo.");
		}
		return conversation;
	}

	/** Quem age precisa participar do grupo: sem isso, o painel seria leitura de outra conversa. */
	private ConversationParticipant requireParticipant(String conversationId, String userId) {
		return repository.findParticipant(conversationId, userId)
			.orElseThrow(() -> new ApiException(403, "NOT_PARTICIPANT", "Voce nao participa desta conversa."));
	}

	private void requireManager(Conversation conversation, ConversationParticipant actor) {
		if (!roleOf(conversation, actor).manages()) {
			throw new ApiException(403, "GROUP_ADMIN_REQUIRED",
				"Somente o dono ou um administrador pode fazer isso.");
		}
	}

	/** O dono e o criador da conversa; o resto vem do papel gravado na linha. */
	private static ConversationRole roleOf(Conversation conversation, ConversationParticipant participant) {
		return conversation.createdBy().equals(participant.userId()) ? ConversationRole.OWNER : participant.role();
	}

	private static String requireTargetId(String rawTargetId) {
		String value = rawTargetId == null ? "" : rawTargetId.trim();
		if (value.isEmpty()) {
			throw new ApiException(400, "INVALID_PARTICIPANT", "Informe a conta.");
		}
		if (value.length() > MAX_ID_LENGTH || !ID_FORMAT.matcher(value).matches()) {
			throw new ApiException(400, "INVALID_PARTICIPANT", "Identificador de conta invalido.");
		}
		return value;
	}

	private static ConversationRole parseRole(String rawRole) {
		String value = rawRole == null ? "" : rawRole.trim().toUpperCase(Locale.ROOT);
		if ("ADMIN".equals(value)) {
			return ConversationRole.ADMIN;
		}
		if ("MEMBER".equals(value)) {
			return ConversationRole.MEMBER;
		}
		throw new ApiException(400, "INVALID_ROLE", "Papel invalido: use ADMIN ou MEMBER.");
	}

	// ------------------------------------------------------------------ chaves

	/**
	 * O mapa de chaves da mensagem (JSON: conta -&gt; chave cifrada).
	 *
	 * <p>Mensagem no formato antigo — a chave vivia na conversa, nao na mensagem — nao aceita
	 * concessao: escrever um mapa nela trocaria o formato da mensagem e quebraria a leitura de quem
	 * ja tinha a chave da conversa.</p>
	 */
	private Map<String, String> readKeys(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new ApiException(409, "HISTORY_KEYS_NOT_SUPPORTED",
				"Esta mensagem usa a chave antiga da conversa; a concessao vale para mensagens com chave por mensagem.");
		}
		try {
			return new LinkedHashMap<>(
				objectMapper.readValue(raw, new TypeReference<LinkedHashMap<String, String>>() {
				}));
		}
		catch (JacksonException exception) {
			throw new ApiException(409, "HISTORY_KEYS_NOT_SUPPORTED",
				"O formato das chaves desta mensagem nao permite concessao.");
		}
	}

	private String writeKeys(Map<String, String> keys) {
		try {
			return objectMapper.writeValueAsString(keys);
		}
		catch (JacksonException exception) {
			throw new ApiException(500, "JSON_SERIALIZATION_FAILURE",
				"Nao foi possivel montar o mapa de chaves da mensagem.");
		}
	}

	// ------------------------------------------------------------------ avisos

	private static List<ConversationParticipant> withParticipant(List<ConversationParticipant> participants,
			ConversationParticipant added) {
		List<ConversationParticipant> all = new java.util.ArrayList<>(participants);
		all.add(added);
		return all;
	}

	/** Avisa quem ficou com a conversa aberta que a lista de participantes mudou. */
	private void publishChange(String conversationId, List<ConversationParticipant> participants) {
		events.publishEvent(new ConversationChanged(conversationId,
			participants.stream().map(ConversationParticipant::userId).toList()));
	}

	private static String groupTitle(Conversation conversation) {
		String title = conversation.title();
		return title == null || title.isBlank() ? "Grupo de mensagens" : title;
	}
}
