package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataConversationParticipantJpaRepository extends JpaRepository<ConversationParticipantJpaEntity, Long> {

	List<ConversationParticipantJpaEntity> findByConversationId(String conversationId);

	Optional<ConversationParticipantJpaEntity> findByConversationIdAndUserId(String conversationId, String userId);

	boolean existsByConversationIdAndUserId(String conversationId, String userId);

}
