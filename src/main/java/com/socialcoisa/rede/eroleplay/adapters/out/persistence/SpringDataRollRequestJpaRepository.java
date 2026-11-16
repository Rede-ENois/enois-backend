package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataRollRequestJpaRepository extends JpaRepository<RollRequestJpaEntity, String> {

	List<RollRequestJpaEntity> findByCampaignIdOrderByCreatedAtDesc(String campaignId);

	Optional<RollRequestJpaEntity> findByCampaignIdAndId(String campaignId, String id);

	List<RollRequestJpaEntity> findByCampaignIdAndPlayerIdOrderByCreatedAtDesc(String campaignId, String playerId);
}
