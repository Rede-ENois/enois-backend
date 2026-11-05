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
}
