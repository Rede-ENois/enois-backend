package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataConversationParticipantJpaRepository extends JpaRepository<ConversationParticipantJpaEntity, Long> {

	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query("""
			UPDATE ConversationParticipantJpaEntity cp SET cp.hiddenAt = :now
			WHERE cp.userId = :userId AND cp.conversationId IN :ids""")
	int hideConversations(@org.springframework.data.repository.query.Param("userId") String userId,
			@org.springframework.data.repository.query.Param("ids") java.util.Collection<String> ids,
			@org.springframework.data.repository.query.Param("now") java.time.Instant now);

	/** Tira o "apagado para mim" das conversas escolhidas (volta para a caixa de entrada). */
	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query("""
			UPDATE ConversationParticipantJpaEntity cp SET cp.hiddenAt = null
			WHERE cp.userId = :userId AND cp.conversationId IN :ids""")
	int unhideConversations(@org.springframework.data.repository.query.Param("userId") String userId,
			@org.springframework.data.repository.query.Param("ids") java.util.Collection<String> ids);

	List<ConversationParticipantJpaEntity> findByConversationId(String conversationId);

	Optional<ConversationParticipantJpaEntity> findByConversationIdAndUserId(String conversationId, String userId);

	boolean existsByConversationIdAndUserId(String conversationId, String userId);

	/**
	 * Total de mensagens nao lidas em TODAS as conversas ativas do usuario (uma query so), opcionalmente
	 * so as de um contexto (caixa social ou profissional).
	 */
	@org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM("
		+ " (SELECT COUNT(m) FROM MessageJpaEntity m"
		+ " WHERE m.conversationId = cp.conversationId"
		+ " AND m.createdAt > COALESCE(cp.lastReadAt, cp.joinedAt))"
		+ " ), 0)"
		+ " FROM ConversationParticipantJpaEntity cp, ConversationJpaEntity c"
		+ " WHERE c.id = cp.conversationId AND cp.userId = :userId AND cp.hiddenAt IS NULL"
		+ SpringDataConversationJpaRepository.CONTEXT_FILTER)
	int countTotalUnread(@org.springframework.data.repository.query.Param("userId") String userId,
			@org.springframework.data.repository.query.Param("context") String context);

}
