package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "github_contribution_events")
class GitHubContributionEventJpaEntity {

	@Id
	private String id;

	@Column(name = "contribution_id", nullable = false)
	private String contributionId;

	@Column(name = "actor_user_id")
	private String actorUserId;

	@Column(nullable = false, length = 40)
	private String action;

	@Column(name = "from_status", length = 24)
	private String fromStatus;

	@Column(name = "to_status", length = 24)
	private String toStatus;

	@Column(length = 1000)
	private String note;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected GitHubContributionEventJpaEntity() {
	}

	static GitHubContributionEventJpaEntity create(String id, String contributionId, String actorUserId,
			String action, String fromStatus, String toStatus, String note, Instant at) {
		GitHubContributionEventJpaEntity e = new GitHubContributionEventJpaEntity();
		e.id = id;
		e.contributionId = contributionId;
		e.actorUserId = actorUserId;
		e.action = action;
		e.fromStatus = fromStatus;
		e.toStatus = toStatus;
		e.note = note;
		e.createdAt = at;
		return e;
	}

	com.socialcoisa.rede.contributions.application.port.out.ContributionAuditRepository.AuditEvent toDomain() {
		return new com.socialcoisa.rede.contributions.application.port.out.ContributionAuditRepository.AuditEvent(
			id, contributionId, actorUserId, action, fromStatus, toStatus, note, createdAt);
	}
}
