package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.contributions.application.port.out.ContributionAuditRepository;

@Repository
class ContributionAuditPersistenceAdapter implements ContributionAuditRepository {

	private final SpringDataGitHubContributionEventJpaRepository repository;

	ContributionAuditPersistenceAdapter(SpringDataGitHubContributionEventJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public void append(String contributionId, String actorUserId, String action, String fromStatus,
			String toStatus, String note, Instant at) {
		repository.save(GitHubContributionEventJpaEntity.create(
			"evt_" + UUID.randomUUID().toString().replace("-", ""), contributionId, actorUserId, action,
			fromStatus, toStatus, note, at));
	}

	@Override
	public List<AuditEvent> findByContribution(String contributionId, int limit) {
		return repository.findByContributionIdOrderByCreatedAtDesc(contributionId, PageRequest.of(0, limit))
			.stream().map(GitHubContributionEventJpaEntity::toDomain).toList();
	}
}
