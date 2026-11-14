package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCampaignMessageJpaRepository extends JpaRepository<CampaignMessageJpaEntity, String> {

	List<CampaignMessageJpaEntity> findByCampaignIdAndChannelOrderByCreatedAtDesc(String campaignId, String channel,
			Pageable pageable);

	List<CampaignMessageJpaEntity> findByCampaignIdAndPinnedAtNotNullOrderByPinnedAtDesc(String campaignId,
			Pageable pageable);
}
