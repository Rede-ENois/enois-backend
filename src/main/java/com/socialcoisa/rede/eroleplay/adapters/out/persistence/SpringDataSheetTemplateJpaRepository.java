package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataSheetTemplateJpaRepository extends JpaRepository<SheetTemplateJpaEntity, String> {

	Optional<SheetTemplateJpaEntity> findByCampaignId(String campaignId);

	boolean existsByCampaignId(String campaignId);
}