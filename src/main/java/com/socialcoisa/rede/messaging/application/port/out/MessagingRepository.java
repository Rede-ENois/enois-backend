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

	void saveParticipant(ConversationParticipant participant);

	Optional<Conversation> findConversationById(String id);

	/** Inbox page; a null context keeps every conversation. */
	List<Conversation> findConversationsByUserId(String userId, java.time.Instant before, ConversationContext context,
			int limit);

	/** As conversas que a pessoa arquivou (apagadas so para ela); contexto nulo = todas. */
	List<Conversation> findArchivedConversationsByUserId(String userId, java.time.Instant before,
			ConversationContext context, int limit);

	List<ConversationParticipant> findParticipantsByConversation(String conversationId);

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

	/** Total de mensagens nao lidas nas conversas ativas do usuario; contexto nulo = todas. */
	int countTotalUnread(String userId, ConversationContext context);

	void saveMessage(Message message);

	List<Message> findMessagesByConversation(String conversationId, String viewerId, java.time.Instant before,
			int limit);

	Optional<Message> findLastMessage(String conversationId);

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
