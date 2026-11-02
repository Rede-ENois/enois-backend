package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.messaging.domain.ConversationContext;

interface SpringDataConversationJpaRepository extends JpaRepository<ConversationJpaEntity, String> {

	/**
	 * The 1:1 conversation of one KIND between exactly two people. Each kind is its own thread:
	 * a social DM (DIRECT) never receives an application (JOB) or a hiring request (SERVICE), so
	 * the social and professional inboxes never share a conversation. The participant count keeps
	 * a group with the same two people from matching.
	 */
	@Query("""
			SELECT c FROM ConversationJpaEntity c
			WHERE c.kind = :kind
			AND c.id IN (SELECT cp.conversationId FROM ConversationParticipantJpaEntity cp WHERE cp.userId = :userA)
			AND c.id IN (SELECT cp.conversationId FROM ConversationParticipantJpaEntity cp WHERE cp.userId = :userB)
			AND (SELECT COUNT(all_) FROM ConversationParticipantJpaEntity all_ WHERE all_.conversationId = c.id) = 2
			ORDER BY c.updatedAt DESC""")
	List<ConversationJpaEntity> findPairByKind(@Param("userA") String userA, @Param("userB") String userB,
			@Param("kind") String kind, Pageable pageable);

	/**
	 * Optional context filter shared by the inbox queries: a null context keeps every conversation
	 * (older clients), otherwise only the kinds of that interface.
	 */
	String CONTEXT_FILTER = " AND (:context IS NULL"
		+ " OR (:context = 'PROFESSIONAL' AND c.kind IN " + ConversationContext.PROFESSIONAL_KINDS_JPQL + ")"
		+ " OR (:context = 'SOCIAL' AND c.kind NOT IN " + ConversationContext.PROFESSIONAL_KINDS_JPQL + "))";

	/** The person's conversations, without the ones they hid ("delete for me"). */
	@Query("SELECT c FROM ConversationJpaEntity c"
		+ " WHERE c.id IN (SELECT cp.conversationId FROM ConversationParticipantJpaEntity cp"
		+ " WHERE cp.userId = :userId AND cp.hiddenAt IS NULL)"
		+ " AND (:before IS NULL OR c.updatedAt < :before)" + CONTEXT_FILTER
		+ " ORDER BY c.updatedAt DESC")
	List<ConversationJpaEntity> findByParticipantUserId(@Param("userId") String userId, @Param("before") Instant before,
			@Param("context") String context, Pageable pageable);

	/** As que a pessoa apagou para si ("arquivadas"): a caixa de entrada esconde, esta lista mostra. */
	@Query("SELECT c FROM ConversationJpaEntity c"
		+ " WHERE c.id IN (SELECT cp.conversationId FROM ConversationParticipantJpaEntity cp"
		+ " WHERE cp.userId = :userId AND cp.hiddenAt IS NOT NULL)"
		+ " AND (:before IS NULL OR c.updatedAt < :before)" + CONTEXT_FILTER
		+ " ORDER BY c.updatedAt DESC")
	List<ConversationJpaEntity> findArchivedByParticipantUserId(@Param("userId") String userId,
			@Param("before") Instant before, @Param("context") String context, Pageable pageable);

}
