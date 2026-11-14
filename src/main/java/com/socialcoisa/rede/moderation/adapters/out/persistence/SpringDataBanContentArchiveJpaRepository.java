package com.socialcoisa.rede.moderation.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataBanContentArchiveJpaRepository extends JpaRepository<BanContentArchiveJpaEntity, String> {

	List<BanContentArchiveJpaEntity> findBySanctionId(String sanctionId);

	java.util.Optional<BanContentArchiveJpaEntity> findFirstBySanctionIdAndUserIdAndContentTypeAndContentIdOrderByArchivedAtDesc(
		String sanctionId, String userId, String contentType, String contentId);
}
