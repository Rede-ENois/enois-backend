package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataMessageJpaRepository extends JpaRepository<MessageJpaEntity, String> {

	List<MessageJpaEntity> findByConversationIdOrderByCreatedAtDesc(String conversationId,
			org.springframework.data.domain.Pageable pageable);

	Optional<MessageJpaEntity> findTopByConversationIdOrderByCreatedAtDesc(String conversationId);

	@Query("""
			SELECT COUNT(m) FROM MessageJpaEntity m
			WHERE m.conversationId = :conversationId
			AND m.createdAt > :lastReadAt""")
	int countUnreadAfter(@Param("conversationId") String conversationId, @Param("lastReadAt") java.time.Instant lastReadAt);

}
