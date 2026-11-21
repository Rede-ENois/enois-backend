package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.messaging.application.port.out.ConversationTopicRepository;
import com.socialcoisa.rede.messaging.domain.ConversationTopic;

@Repository
class ConversationTopicPersistenceAdapter implements ConversationTopicRepository {

	private final SpringDataConversationTopicJpaRepository topics;

	ConversationTopicPersistenceAdapter(SpringDataConversationTopicJpaRepository topics) {
		this.topics = topics;
	}

	@Override
	public List<ConversationTopic> findTopicsByConversation(String conversationId) {
		return topics.findByConversationIdOrderByPositionAsc(conversationId).stream()
			.map(ConversationTopicJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Optional<ConversationTopic> findTopic(String conversationId, String topicId) {
		return topics.findByIdAndConversationId(topicId, conversationId).map(ConversationTopicJpaEntity::toDomain);
	}

	@Override
	public boolean topicNameExists(String conversationId, String name) {
		return topics.existsByConversationIdAndName(conversationId, name);
	}

	@Override
	public long countTopics(String conversationId) {
		return topics.countByConversationId(conversationId);
	}

	@Override
	public void saveTopic(ConversationTopic topic) {
		topics.save(ConversationTopicJpaEntity.fromDomain(topic));
	}

}
