package com.socialcoisa.rede.e2e.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataConversationKeyRepository extends JpaRepository<ConversationKeyJpaEntity, ConversationKeyId> {
	Optional<ConversationKeyJpaEntity> findByConversationIdAndUserId(String conversationId, String userId);
	List<ConversationKeyJpaEntity> findByConversationId(String conversationId);
}
