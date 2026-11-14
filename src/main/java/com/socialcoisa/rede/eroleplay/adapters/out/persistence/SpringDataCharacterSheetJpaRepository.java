package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCharacterSheetJpaRepository extends JpaRepository<CharacterSheetJpaEntity, String> {

	Optional<CharacterSheetJpaEntity> findByCampaignIdAndUserId(String campaignId, String userId);

	List<CharacterSheetJpaEntity> findByCampaignIdOrderByUpdatedAtDesc(String campaignId);

	long countByCampaignId(String campaignId);
}