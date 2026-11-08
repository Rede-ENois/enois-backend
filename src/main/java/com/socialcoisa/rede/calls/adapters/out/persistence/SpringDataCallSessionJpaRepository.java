package com.socialcoisa.rede.calls.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.calls.domain.CallState;

interface SpringDataCallSessionJpaRepository extends JpaRepository<CallSessionJpaEntity, String> {

	List<CallSessionJpaEntity> findByConversationIdAndStateIn(String conversationId, Collection<CallState> states);

	List<CallSessionJpaEntity> findByGroupIdAndStateIn(String groupId, Collection<CallState> states);

	List<CallSessionJpaEntity> findByGroupIdAndTopicIdAndStateIn(String groupId, String topicId,
			Collection<CallState> states);

	/** Salas abertas do grupo que NÃO são de tópico (sala geral, e as de antes do tópico existir). */
	@Query("SELECT c FROM CallSessionJpaEntity c WHERE c.groupId = :groupId AND c.topicId IS NULL "
			+ "AND c.state IN :states")
	List<CallSessionJpaEntity> findGroupWideOpen(@Param("groupId") String groupId,
			@Param("states") Collection<CallState> states);

	/** Salas abertas do encontro. O evento tem uma sala só: a primeira aberta é a sala dele. */
	@Query("SELECT c FROM CallSessionJpaEntity c WHERE c.eventId = :eventId AND c.state IN :states")
	List<CallSessionJpaEntity> findOpenByEvent(@Param("eventId") String eventId,
			@Param("states") Collection<CallState> states);

	@Query("SELECT c FROM CallSessionJpaEntity c WHERE c.state IN :states AND c.expiresAt < :now ORDER BY c.expiresAt")
	List<CallSessionJpaEntity> expired(@Param("states") Collection<CallState> states, @Param("now") Instant now,
			Pageable pageable);

	@Query("SELECT c FROM CallSessionJpaEntity c WHERE c.state IN :states AND c.id IN "
			+ "(SELECT p.callId FROM CallParticipantJpaEntity p WHERE p.userId = :userId) ORDER BY c.createdAt DESC")
	List<CallSessionJpaEntity> openWithParticipant(@Param("userId") String userId,
			@Param("states") Collection<CallState> states);

	Optional<CallSessionJpaEntity> findByRoomId(String roomId);

	/** Compare-and-set do estado: só grava se a linha ainda estiver em {@code from}. */
	@Modifying(clearAutomatically = true)
	@Query("UPDATE CallSessionJpaEntity c SET c.state = :to, c.answeredAt = :answeredAt, c.endedAt = :endedAt, "
			+ "c.endedBy = :endedBy, c.endReason = :endReason, c.expiresAt = :expiresAt, "
			+ "c.lastActivityAt = :now WHERE c.id = :id AND c.state = :from")
	int advance(@Param("id") String id, @Param("from") CallState from, @Param("to") CallState to,
			@Param("now") Instant now, @Param("answeredAt") Instant answeredAt, @Param("endedAt") Instant endedAt,
			@Param("endedBy") String endedBy, @Param("endReason") String endReason,
			@Param("expiresAt") Instant expiresAt);

	/**
	 * Chamada que fica aberta sem mudar de estado: só o prazo (e o sinal de vida) muda. É o que
	 * segura a sala vazia na tolerância e o que adia o abandono quando alguém entra.
	 */
	@Modifying(clearAutomatically = true)
	@Query("UPDATE CallSessionJpaEntity c SET c.expiresAt = :expiresAt, c.lastActivityAt = :now "
			+ "WHERE c.id = :id AND c.state = :state")
	int extend(@Param("id") String id, @Param("state") CallState state, @Param("expiresAt") Instant expiresAt,
			@Param("now") Instant now);
}
