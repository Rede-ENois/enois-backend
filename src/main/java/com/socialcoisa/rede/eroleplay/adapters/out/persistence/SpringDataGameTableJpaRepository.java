package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataGameTableJpaRepository extends JpaRepository<GameTableJpaEntity, String> {

	List<GameTableJpaEntity> findByCampaignIdOrderByCreatedAtDesc(String campaignId);

	Optional<GameTableJpaEntity> findByIdAndCampaignId(String id, String campaignId);
}
