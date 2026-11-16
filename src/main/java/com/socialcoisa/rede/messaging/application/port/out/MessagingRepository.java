package com.socialcoisa.rede.messaging.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.messaging.domain.Conversation;
import com.socialcoisa.rede.messaging.domain.ConversationContext;
import com.socialcoisa.rede.messaging.domain.ConversationParticipant;
import com.socialcoisa.rede.messaging.domain.Message;

public interface MessagingRepository {

	void saveConversation(Conversation conversation);

	/**
	 * Moves the conversation to the top of the inbox in one statement, which also locks its row until
	 * the transaction ends. Sending a message does this FIRST: inserting the message takes a shared
	 * lock on the conversation (foreign key), and two senders upgrading it to write the conversation
	 * afterwards deadlocked each other. Locked first, concurrent sends of one conversation queue.
	 */
	void touchConversation(String conversationId, Instant at);

	void saveParticipant(ConversationParticipant participant);

	Optional<Conversation> findConversationById(String id);

	/** Inbox page; a null context keeps every conversation. */
	List<Conversation> findConversationsByUserId(String userId, java.time.Instant before, ConversationContext context,
			int limit);

	/** As conversas que a pessoa arquivou (apagadas so para ela); contexto nulo = todas. */
	List<Conversation> findArchivedConversationsByUserId(String userId, java.time.Instant before,
			ConversationContext context, int limit);

	List<ConversationParticipant> findParticipantsByConversation(String conversationId);

	/** Participantes de varias conversas em uma consulta (lista de conversas do app). */
	List<ConversationParticipant> findParticipantsByConversations(java.util.Collection<String> conversationIds);

	/** The 1:1 conversation of that kind (DIRECT, JOB, SERVICE) between two people, if any. */
	Optional<Conversation> findPairByKind(String userA, String userB, String kind);

	Optional<ConversationParticipant> findParticipant(String conversationId, String userId);

	boolean isParticipant(String conversationId, String userId);

	/** Esconde conversas para um usuario ("apagar para mim"); nao afeta os outros participantes. */
	int hideConversations(String userId, Collection<String> conversationIds, java.time.Instant now);

	/** Desfaz o "apagar para mim" (a conversa volta para a caixa de entrada). */
	int unhideConversations(String userId, Collection<String> conversationIds);

	void markRead(String conversationId, String userId, java.time.Instant now);

	int countUnread(String conversationId, String userId);

	/**
	 * Nao-lidas de varias conversas do leitor em uma consulta. Conversa sem registro de leitura
	 * conta tudo, como o {@link #countUnread(String, String)} faz para uma.
	 *
	 * <p>A mensagem que o leitor apagou para si ("apagar para mim", {@code message_hidden}) nao
	 * conta: ela ja nao aparece na conversa, entao tambem nao pode inflar o selo.</p>
	 */
	java.util.Map<String, Integer> countUnreadByConversations(java.util.Collection<String> conversationIds,
			String userId);

	/**
	 * Total de mensagens nao lidas nas conversas ativas do usuario; contexto nulo = todas. Mesma
	 * regra da {@link #countUnreadByConversations}: o que a pessoa apagou para si nao conta.
	 */
	int countTotalUnread(String userId, ConversationContext context);

	void saveMessage(Message message);

	/**
	 * Uma pagina de mensagens anteriores a {@code (before, beforeId)}. O par e o criterio porque o
	 * instante sozinho empata entre mensagens do mesmo segundo, e a pagina seguinte pularia a que
	 * empatou. {@code before} nulo comeca pela mais recente.
	 */
	List<Message> findMessagesByConversation(String conversationId, String viewerId, java.time.Instant before,
			String beforeId, int limit);

	Optional<Message> findLastMessage(String conversationId);

	/**
	 * A ultima mensagem VISIVEL para {@code viewerId}: a que ele apagou para si nao serve de
	 * previa (vale para a conversa aberta, como para a lista).
	 */
	Optional<Message> findLastVisibleMessage(String conversationId, String viewerId);

	/**
	 * A ultima mensagem de cada conversa do lote, em uma consulta (lista de conversas do app).
	 *
	 * <p>Mensagem apagada para mim nao serve de previa: a pessoa limitou a lista e o texto nao
	 * pode reaparecer na caixa de entrada.</p>
	 */
	java.util.Map<String, Message> findLastMessagesByConversations(java.util.Collection<String> conversationIds,
			String viewerId);

	/** Mensagem idêntica do mesmo remetente na conversa desde um instante (duplo clique/retry). */
	Optional<Message> findRecentDuplicate(String conversationId, String senderId, String text, java.time.Instant since);

	/** A mensagem por id (para conferir de qual conversa ela e antes de mexer nela). */
	Optional<Message> findMessageById(String messageId);

	/** Mensagens por ids em batch (para montar os previews de reply sem N+1). */
	List<Message> findMessagesByIds(Collection<String> messageIds);

	long countMessagesInConversation(String conversationId, Collection<String> messageIds);

	/** Apaga mensagens SO para quem chama (o outro participante mantem). */
	int hideMessages(String userId, Collection<String> messageIds, java.time.Instant now);

	/** Ids de mensagem que esta pessoa apagou para si (a listagem esconde). */
	Set<String> hiddenMessageIds(String userId, Collection<String> messageIds);

	boolean addMessageReaction(String id, String messageId, String userId, com.socialcoisa.rede.feed.domain.ReactionType type, java.time.Instant createdAt);
	boolean removeMessageReaction(String messageId, String userId);
	List<ReactionSummary> findReactionsByMessageId(String messageId);

	/** Reações de várias mensagens numa consulta (resumo por mensagem no histórico). */
	List<MessageReactionRow> findReactionsByMessageIds(java.util.Collection<String> messageIds);

	record MessageReactionRow(String messageId, String userId, com.socialcoisa.rede.feed.domain.ReactionType type) {
	}

	record ReactionSummary(String userId, com.socialcoisa.rede.feed.domain.ReactionType type, java.time.Instant createdAt) {}

	/** Todas as mensagens enviadas por um usuario — usado pelo ban cleanup. */
	List<Message> findAllBySender(String senderId);
}
