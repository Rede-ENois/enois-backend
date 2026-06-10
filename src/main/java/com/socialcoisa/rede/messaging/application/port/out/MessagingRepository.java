package com.socialcoisa.rede.messaging.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.messaging.domain.Conversation;
import com.socialcoisa.rede.messaging.domain.ConversationParticipant;
import com.socialcoisa.rede.messaging.domain.Message;

public interface MessagingRepository {

	void saveConversation(Conversation conversation);

	void saveParticipant(ConversationParticipant participant);

	Optional<Conversation> findConversationById(String id);

	List<Conversation> findConversationsByUserId(String userId, java.time.Instant before, int limit);

	List<ConversationParticipant> findParticipantsByConversation(String conversationId);

	Optional<ConversationParticipant> findParticipant(String conversationId, String userId);

	boolean isParticipant(String conversationId, String userId);

	void markRead(String conversationId, String userId, java.time.Instant now);

	int countUnread(String conversationId, String userId);

	void saveMessage(Message message);

	List<Message> findMessagesByConversation(String conversationId, String cursor, int limit);

	Optional<Message> findLastMessage(String conversationId);

	boolean addMessageReaction(String id, String messageId, String userId, com.socialcoisa.rede.feed.domain.ReactionType type, java.time.Instant createdAt);
	boolean removeMessageReaction(String messageId, String userId);
	List<ReactionSummary> findReactionsByMessageId(String messageId);

	record ReactionSummary(String userId, com.socialcoisa.rede.feed.domain.ReactionType type, java.time.Instant createdAt) {}
}
