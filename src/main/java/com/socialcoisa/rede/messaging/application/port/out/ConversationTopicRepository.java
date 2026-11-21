package com.socialcoisa.rede.messaging.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.messaging.domain.ConversationTopic;

/** Topicos do grupo de conversa: persistencia propria, para nao inflar a porta da conversa. */
public interface ConversationTopicRepository {

	List<ConversationTopic> findTopicsByConversation(String conversationId);

	Optional<ConversationTopic> findTopic(String conversationId, String topicId);

	boolean topicNameExists(String conversationId, String name);

	long countTopics(String conversationId);

	void saveTopic(ConversationTopic topic);

}
