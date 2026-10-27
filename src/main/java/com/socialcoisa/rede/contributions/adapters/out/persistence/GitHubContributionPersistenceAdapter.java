package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.contributions.application.port.out.GitHubContributionRepository;
import com.socialcoisa.rede.contributions.domain.ContributionCategory;
import com.socialcoisa.rede.contributions.domain.ContributionKind;
import com.socialcoisa.rede.contributions.domain.ContributionStatus;
import com.socialcoisa.rede.contributions.domain.GitHubContribution;

@Repository
class GitHubContributionPersistenceAdapter implements GitHubContributionRepository {

	private final SpringDataGitHubContributionJpaRepository repository;

	GitHubContributionPersistenceAdapter(SpringDataGitHubContributionJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public GitHubContribution save(GitHubContribution contribution) {
		return repository.save(GitHubContributionJpaEntity.fromDomain(contribution)).toDomain();
	}

	@Override
	public Optional<GitHubContribution> findById(String id) {
		return repository.findById(id).map(GitHubContributionJpaEntity::toDomain);
	}

	@Override
	public Optional<GitHubContribution> findByRepositoryAndKindAndNumber(String repositoryName,
			ContributionKind kind, int number) {
		return repository.findByRepositoryAndKindAndObjectNumber(repositoryName, kind, number)
			.map(GitHubContributionJpaEntity::toDomain);
	}

	@Override
	public List<GitHubContribution> list(ContributionStatus status, ContributionKind kind, int limit) {
		PageRequest page = PageRequest.of(0, limit);
		List<GitHubContributionJpaEntity> rows;
		if (status == null && kind == null) {
			rows = repository.findAllByOrderByUpdatedAtDesc(page);
		}
		else if (status == null) {
			rows = repository.findByKindOrderByUpdatedAtDesc(kind, page);
		}
		else if (kind == null) {
			rows = repository.findByStatusOrderByUpdatedAtDesc(status, page);
		}
		else {
			rows = repository.findByStatusAndKindOrderByUpdatedAtDesc(status, kind, page);
		}
		return rows.stream().map(GitHubContributionJpaEntity::toDomain).toList();
	}

	@Override
	public List<GitHubContribution> findCountableByUser(String userId, ContributionCategory category, int limit) {
		return repository.findByUserIdAndStatusAndRevertedFalseAndCategoryOrderByUpdatedAtDesc(userId,
			ContributionStatus.VALIDATED, category, PageRequest.of(0, limit)).stream()
			.map(GitHubContributionJpaEntity::toDomain).toList();
	}

	@Override
	public int countCountableByUser(String userId, ContributionCategory category) {
		return repository.countByUserIdAndStatusAndRevertedFalseAndCategory(userId, ContributionStatus.VALIDATED,
			category);
	}

	@Override
	public List<GitHubContribution> findAllByUser(String userId, int limit) {
		return repository.findByUserIdOrderByUpdatedAtDesc(userId, PageRequest.of(0, limit)).stream()
			.map(GitHubContributionJpaEntity::toDomain).toList();
	}
}
