package com.socialcoisa.rede.calls.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.calls.domain.CallParticipantState;

interface SpringDataCallParticipantJpaRepository extends JpaRepository<CallParticipantJpaEntity, String> {

	Optional<CallParticipantJpaEntity> findByCallIdAndUserId(String callId, String userId);

	List<CallParticipantJpaEntity> findByCallId(String callId);

	List<CallParticipantJpaEntity> findByCallIdAndJoinedAtIsNotNullOrderByJoinedAtAsc(String callId);

	long countByCallIdAndState(String callId, CallParticipantState state);

	long countByCallIdAndStateAndUserIdNot(String callId, CallParticipantState state, String excludedUserId);

	long countByCallIdAndStateAndUserIdIn(String callId, CallParticipantState state, Collection<String> userIds);

	/**
	 * Entrar (ou sair) só muda a linha se ela ainda estiver em {@code from}. Devolve quantas linhas
	 * casaram: zero quer dizer que outra requisição já fez a mesma coisa, e o chamador decide o que
	 * responder sem contar duas vezes.
	 */
	@Modifying(clearAutomatically = true)
	@Query("UPDATE CallParticipantJpaEntity p SET p.state = :to, p.joinedAt = :joinedAt, p.leftAt = :leftAt, "
			+ "p.lastSeenAt = :now WHERE p.callId = :callId AND p.userId = :userId AND p.state = :from")
	int advance(@Param("callId") String callId, @Param("userId") String userId,
			@Param("from") CallParticipantState from, @Param("to") CallParticipantState to,
			@Param("joinedAt") Instant joinedAt, @Param("leftAt") Instant leftAt, @Param("now") Instant now);

	/** Mesma troca de {@link #advance}, mas preserva o horário em que a pessoa entrou pela primeira vez. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE CallParticipantJpaEntity p SET p.state = :to, p.joinedAt = COALESCE(p.joinedAt, :now), "
			+ "p.leftAt = NULL, p.lastSeenAt = :now "
			+ "WHERE p.callId = :callId AND p.userId = :userId AND p.state = :from")
	int advanceKeepingJoin(@Param("callId") String callId, @Param("userId") String userId,
			@Param("from") CallParticipantState from, @Param("to") CallParticipantState to,
			@Param("now") Instant now);

	@Modifying(clearAutomatically = true)
	@Query("UPDATE CallParticipantJpaEntity p SET p.state = :to, p.leftAt = :now, p.lastSeenAt = :now "
			+ "WHERE p.callId = :callId AND p.state = :from")
	int settle(@Param("callId") String callId, @Param("from") CallParticipantState from,
			@Param("to") CallParticipantState to, @Param("now") Instant now);

	@Modifying(clearAutomatically = true)
	@Query("UPDATE CallParticipantJpaEntity p SET p.state = :to, p.leftAt = :now, p.lastSeenAt = :now "
			+ "WHERE p.callId = :callId AND p.state = :from")
	int settleAllPresent(@Param("callId") String callId, @Param("from") CallParticipantState from,
			@Param("to") CallParticipantState to, @Param("now") Instant now);
}
