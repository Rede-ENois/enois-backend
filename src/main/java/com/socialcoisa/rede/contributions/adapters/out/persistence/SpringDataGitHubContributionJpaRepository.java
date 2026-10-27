package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.socialcoisa.rede.contributions.domain.ContributionCategory;
import com.socialcoisa.rede.contributions.domain.ContributionKind;
import com.socialcoisa.rede.contributions.domain.ContributionStatus;

interface SpringDataGitHubContributionJpaRepository extends JpaRepository<GitHubContributionJpaEntity, String> {

	Optional<GitHubContributionJpaEntity> findByRepositoryAndKindAndObjectNumber(String repository,
			ContributionKind kind, int objectNumber);

	List<GitHubContributionJpaEntity> findByStatusOrderByUpdatedAtDesc(ContributionStatus status, Pageable pageable);

	List<GitHubContributionJpaEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);

	List<GitHubContributionJpaEntity> findByKindOrderByUpdatedAtDesc(ContributionKind kind, Pageable pageable);

	List<GitHubContributionJpaEntity> findByStatusAndKindOrderByUpdatedAtDesc(ContributionStatus status,
			ContributionKind kind, Pageable pageable);

	List<GitHubContributionJpaEntity> findByUserIdAndStatusAndRevertedFalseAndCategoryOrderByUpdatedAtDesc(
			String userId, ContributionStatus status, ContributionCategory category, Pageable pageable);

	int countByUserIdAndStatusAndRevertedFalseAndCategory(String userId, ContributionStatus status,
			ContributionCategory category);

	List<GitHubContributionJpaEntity> findByUserIdOrderByUpdatedAtDesc(String userId, Pageable pageable);
}
