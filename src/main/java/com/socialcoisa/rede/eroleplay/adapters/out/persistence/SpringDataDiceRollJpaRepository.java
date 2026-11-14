package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataDiceRollJpaRepository extends JpaRepository<DiceRollJpaEntity, String> {

	List<DiceRollJpaEntity> findByCampaignIdOrderByCreatedAtDesc(String campaignId, Pageable pageable);
}
