package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataGitHubContributionEventJpaRepository
		extends JpaRepository<GitHubContributionEventJpaEntity, String> {

	List<GitHubContributionEventJpaEntity> findByContributionIdOrderByCreatedAtDesc(String contributionId,
			Pageable pageable);
}
