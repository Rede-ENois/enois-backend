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

	/** Última mensagem que {@code viewerId} ainda vê (a que ele apagou para si não conta). */
	@Query("""
			SELECT m FROM MessageJpaEntity m
			WHERE m.conversationId = :conversationId
			AND NOT EXISTS (SELECT h FROM MessageHiddenJpaEntity h
				WHERE h.messageId = m.id AND h.userId = :viewerId)
			ORDER BY m.createdAt DESC, m.id DESC""")
	List<MessageJpaEntity> visibleLastMessages(@Param("conversationId") String conversationId,
			@Param("viewerId") String viewerId, org.springframework.data.domain.Pageable pageable);

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

	/**
	 * Nao-lidas de UMA conversa depois do ultimo read. A mensagem que o leitor apagou para si nao
	 * conta (mesma regra da consulta em lote, senao o detalhe e a lista discordam).
	 */
	@Query("""
			SELECT COUNT(m) FROM MessageJpaEntity m
			WHERE m.conversationId = :conversationId
			AND m.createdAt > :lastReadAt
			AND NOT EXISTS (SELECT h FROM MessageHiddenJpaEntity h
				WHERE h.messageId = m.id AND h.userId = :userId)""")
	int countUnreadAfter(@Param("conversationId") String conversationId, @Param("lastReadAt") java.time.Instant lastReadAt,
			@Param("userId") String userId);

	@Query("SELECT COUNT(m) FROM MessageJpaEntity m WHERE m.conversationId = :conversationId AND m.id IN :messageIds")
	long countInConversation(@Param("conversationId") String conversationId,
			@Param("messageIds") java.util.Collection<String> messageIds);

	int countByConversationId(String conversationId);

	/** Todas as mensagens da conversa MENOS as que o leitor apagou para si (sem read registrado). */
	@Query("""
			SELECT COUNT(m) FROM MessageJpaEntity m
			WHERE m.conversationId = :conversationId
			AND NOT EXISTS (SELECT h FROM MessageHiddenJpaEntity h
				WHERE h.messageId = m.id AND h.userId = :userId)""")
	int countVisibleByConversationId(@Param("conversationId") String conversationId,
			@Param("userId") String userId);

	List<MessageJpaEntity> findBySenderId(String senderId);

	/**
	 * A última mensagem de cada conversa do lote, em UMA consulta: a lista de conversas do app
	 * mostra a prévia de todas elas e não pode disparar uma consulta por conversa.
	 *
	 * <p>A ordem é a MESMA do método individual ({@code createdAt desc}), não o id máximo: o id é
	 * gerado, e a prévia tem de ser a mensagem mais recente, como era antes. O desempate por id
	 * garante UMA linha por conversa quando duas mensagens compartilham o mesmo instante.</p>
	 *
	 * <p>A mensagem que o leitor apagou para si não é prévia: o texto que ele escondeu não pode
	 * reaparecer na lista. Se era a mais recente, a prévia passa a ser a anterior visível.</p>
	 */
	@Query("""
			SELECT m FROM MessageJpaEntity m
			WHERE m.id IN (
				SELECT ult.id FROM (
					SELECT m2.id AS id, ROW_NUMBER() OVER (
						PARTITION BY m2.conversationId ORDER BY m2.createdAt DESC, m2.id DESC) AS rn
					FROM MessageJpaEntity m2 WHERE m2.conversationId IN :conversationIds
					AND NOT EXISTS (SELECT h FROM MessageHiddenJpaEntity h
						WHERE h.messageId = m2.id AND h.userId = :viewerId)
				) ult WHERE ult.rn = 1)
			""")
	List<MessageJpaEntity> findLastMessagesOfConversations(
			@Param("conversationIds") java.util.Collection<String> conversationIds,
			@Param("viewerId") String viewerId);

	/**
	 * Nao-lidas de VARIAS conversas em UMA consulta: para cada participante do leitor, o total de
	 * mensagens da conversa posteriores ao ultimo read (o que o countUnreadAfter calcula por uma).
	 *
	 * <p>Mensagem apagada para mim nao entra: a lista mostraria um selo de mensagem que, ao abrir a
	 * conversa, nao existe.</p>
	 */
	@Query("""
			SELECT m.conversationId AS conversationId, COUNT(m) AS unread FROM MessageJpaEntity m
			WHERE m.conversationId IN :conversationIds
			AND m.createdAt > COALESCE((SELECT p.lastReadAt FROM ConversationParticipantJpaEntity p
				WHERE p.conversationId = m.conversationId AND p.userId = :userId), CAST('1970-01-01T00:00:00Z' AS Instant))
			AND NOT EXISTS (SELECT h FROM MessageHiddenJpaEntity h
				WHERE h.messageId = m.id AND h.userId = :userId)
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
