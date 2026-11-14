package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.eroleplay.domain.CampaignJoinRequestStatus;

interface SpringDataCampaignJoinRequestJpaRepository
		extends JpaRepository<CampaignJoinRequestJpaEntity, String> {

	Optional<CampaignJoinRequestJpaEntity> findByCampaignIdAndUserIdAndStatus(String campaignId, String userId,
			CampaignJoinRequestStatus status);

	List<CampaignJoinRequestJpaEntity> findByCampaignIdAndStatus(String campaignId, CampaignJoinRequestStatus status);

	/**
	 * Decisao atomica: so muda o pedido que ainda esta PENDENTE. Devolve 0 quando outra pessoa
	 * decidiu antes — e o que impede duas aprovacoes simultaneas de virarem duas associacoes.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		UPDATE CampaignJoinRequestJpaEntity r
		SET r.status = :status, r.pendingSlot = null, r.decidedBy = :actorId, r.decidedAt = :now
		WHERE r.id = :requestId AND r.status = :pending""")
	int markDecided(@Param("requestId") String requestId, @Param("status") CampaignJoinRequestStatus status,
			@Param("actorId") String actorId, @Param("now") Instant now,
			@Param("pending") CampaignJoinRequestStatus pending);
}
