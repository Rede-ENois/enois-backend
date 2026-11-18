package com.socialcoisa.rede.groups.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.groups.domain.JoinRequestStatus;

interface SpringDataGroupJoinRequestJpaRepository extends JpaRepository<GroupJoinRequestJpaEntity, String> {

	Optional<GroupJoinRequestJpaEntity> findByGroupIdAndUserIdAndStatus(String groupId, String userId,
			JoinRequestStatus status);

	@Query("""
		SELECT r.groupId FROM GroupJoinRequestJpaEntity r
		WHERE r.groupId IN :groupIds AND r.userId = :userId AND r.status = :status""")
	List<String> findPendingGroupIds(@Param("groupIds") Collection<String> groupIds,
			@Param("userId") String userId, @Param("status") JoinRequestStatus status);

	List<GroupJoinRequestJpaEntity> findByGroupIdAndStatusOrderByCreatedAtAsc(String groupId, JoinRequestStatus status);

	/**
	 * Só decide o que ainda está pendente: o WHERE é a trava contra duas aprovações simultâneas.
	 *
	 * `clearAutomatically` é obrigatório: o update em massa não passa pelo cache de primeiro nível
	 * do JPA, então sem limpar o contexto a leitura seguinte devolveria o pedido ainda PENDENTE.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		UPDATE GroupJoinRequestJpaEntity r
		SET r.status = :status, r.pendingSlot = null, r.decidedBy = :actorId, r.decidedAt = :now
		WHERE r.id = :id AND r.status = :pending""")
	int markDecided(@Param("id") String id, @Param("status") JoinRequestStatus status,
			@Param("pending") JoinRequestStatus pending, @Param("actorId") String actorId, @Param("now") Instant now);

	@Modifying
	@Query("DELETE FROM GroupJoinRequestJpaEntity r WHERE r.groupId = :groupId")
	int deleteByGroupId(@Param("groupId") String groupId);
}
