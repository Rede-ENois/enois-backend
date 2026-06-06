package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataConversationJpaRepository extends JpaRepository<ConversationJpaEntity, String> {

	@Query("""
			SELECT c FROM ConversationJpaEntity c
			WHERE c.id IN (SELECT cp.conversationId FROM ConversationParticipantJpaEntity cp WHERE cp.userId = :userId)
			ORDER BY c.updatedAt DESC""")
	List<ConversationJpaEntity> findByParticipantUserId(@Param("userId") String userId);

}
