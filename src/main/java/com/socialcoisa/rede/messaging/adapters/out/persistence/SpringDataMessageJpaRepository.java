package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataMessageJpaRepository extends JpaRepository<MessageJpaEntity, String> {

	List<MessageJpaEntity> findByConversationIdOrderByCreatedAtDesc(String conversationId,
			org.springframework.data.domain.Pageable pageable);

	List<MessageJpaEntity> findByConversationIdAndCreatedAtLessThanOrderByCreatedAtDesc(String conversationId,
			java.time.Instant before, org.springframework.data.domain.Pageable pageable);

	Optional<MessageJpaEntity> findTopByConversationIdOrderByCreatedAtDesc(String conversationId);

	Optional<MessageJpaEntity>
		findFirstByConversationIdAndSenderIdAndTextAndCreatedAtAfterOrderByCreatedAtDesc(
			String conversationId, String senderId, String text, java.time.Instant since);

	/** Mensagens da conversa SEM as que este leitor apagou para si (filtro no banco: a paginacao
	 *  continua coerente, e nao sobra buraco na pagina). */
	@Query("""
			SELECT m FROM MessageJpaEntity m
			WHERE m.conversationId = :conversationId
			AND NOT EXISTS (SELECT h FROM MessageHiddenJpaEntity h
				WHERE h.messageId = m.id AND h.userId = :viewerId)
			ORDER BY m.createdAt DESC""")
	List<MessageJpaEntity> visibleMessages(@Param("conversationId") String conversationId,
			@Param("viewerId") String viewerId, Pageable pageable);

	@Query("""
			SELECT m FROM MessageJpaEntity m
			WHERE m.conversationId = :conversationId AND m.createdAt < :before
			AND NOT EXISTS (SELECT h FROM MessageHiddenJpaEntity h
				WHERE h.messageId = m.id AND h.userId = :viewerId)
			ORDER BY m.createdAt DESC""")
	List<MessageJpaEntity> visibleMessagesBefore(@Param("conversationId") String conversationId,
			@Param("viewerId") String viewerId, @Param("before") java.time.Instant before, Pageable pageable);

	@Query("""
			SELECT COUNT(m) FROM MessageJpaEntity m
			WHERE m.conversationId = :conversationId
			AND m.createdAt > :lastReadAt""")
	int countUnreadAfter(@Param("conversationId") String conversationId, @Param("lastReadAt") java.time.Instant lastReadAt);

	@Query("SELECT COUNT(m) FROM MessageJpaEntity m WHERE m.conversationId = :conversationId AND m.id IN :messageIds")
	long countInConversation(@Param("conversationId") String conversationId,
			@Param("messageIds") java.util.Collection<String> messageIds);

	int countByConversationId(String conversationId);

	List<MessageJpaEntity> findBySenderId(String senderId);

	/**
	 * A última mensagem de cada conversa do lote, em UMA consulta: a lista de conversas do app
	 * mostra a prévia de todas elas e não pode disparar uma consulta por conversa.
	 */
	@Query("""
			SELECT m FROM MessageJpaEntity m
			WHERE m.id IN (SELECT max(m2.id) FROM MessageJpaEntity m2
				WHERE m2.conversationId IN :conversationIds GROUP BY m2.conversationId)
			""")
	List<MessageJpaEntity> findLastMessagesOfConversations(
			@Param("conversationIds") java.util.Collection<String> conversationIds);

	/**
	 * Nao-lidas de VARIAS conversas em UMA consulta: para cada participante do leitor, o total de
	 * mensagens da conversa posteriores ao ultimo read (o que o countUnreadAfter calcula por uma).
	 */
	@Query("""
			SELECT m.conversationId AS conversationId, COUNT(m) AS unread FROM MessageJpaEntity m
			WHERE m.conversationId IN :conversationIds
			AND m.createdAt > COALESCE((SELECT p.lastReadAt FROM ConversationParticipantJpaEntity p
				WHERE p.conversationId = m.conversationId AND p.userId = :userId), CAST('1970-01-01T00:00:00Z' AS Instant))
			GROUP BY m.conversationId
			""")
	List<UnreadCountByConversation> countUnreadByConversations(
			@Param("conversationIds") java.util.Collection<String> conversationIds,
			@Param("userId") String userId);

	interface UnreadCountByConversation {

		String getConversationId();

		long getUnread();
	}
}
