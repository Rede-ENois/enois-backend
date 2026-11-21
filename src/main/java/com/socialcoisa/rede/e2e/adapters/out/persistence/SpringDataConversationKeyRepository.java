package com.socialcoisa.rede.e2e.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataConversationKeyRepository extends JpaRepository<ConversationKeyJpaEntity, ConversationKeyId> {
	Optional<ConversationKeyJpaEntity> findByConversationIdAndUserId(String conversationId, String userId);
	List<ConversationKeyJpaEntity> findByConversationId(String conversationId);

	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query("""
			DELETE FROM ConversationKeyJpaEntity k
			WHERE k.conversationId = :conversationId AND k.userId = :userId""")
	int deleteConversationKey(@org.springframework.data.repository.query.Param("conversationId") String conversationId,
			@org.springframework.data.repository.query.Param("userId") String userId);
}
