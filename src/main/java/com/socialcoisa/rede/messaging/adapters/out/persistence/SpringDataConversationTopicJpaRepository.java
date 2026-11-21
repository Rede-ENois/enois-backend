package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataConversationTopicJpaRepository extends JpaRepository<ConversationTopicJpaEntity, String> {

	List<ConversationTopicJpaEntity> findByConversationIdOrderByPositionAsc(String conversationId);

	Optional<ConversationTopicJpaEntity> findByIdAndConversationId(String id, String conversationId);

	boolean existsByConversationIdAndName(String conversationId, String name);

	long countByConversationId(String conversationId);

}
