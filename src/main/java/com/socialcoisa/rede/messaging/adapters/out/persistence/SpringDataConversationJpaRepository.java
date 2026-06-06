package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataConversationJpaRepository extends JpaRepository<ConversationJpaEntity, String> {

	@Query("""
			SELECT c FROM ConversationJpaEntity c
			WHERE c.id IN (SELECT cp.conversationId FROM ConversationParticipantJpaEntity cp WHERE cp.userId = :userId)
			AND (:before IS NULL OR c.updatedAt < :before)
			ORDER BY c.updatedAt DESC""")
	List<ConversationJpaEntity> findByParticipantUserId(@Param("userId") String userId, @Param("before") Instant before,
			Pageable pageable);

}
