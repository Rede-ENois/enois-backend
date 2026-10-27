package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.contributions.domain.ContributionCategory;
import com.socialcoisa.rede.contributions.domain.ContributionKind;
import com.socialcoisa.rede.contributions.domain.ContributionStatus;
import com.socialcoisa.rede.contributions.domain.GitHubContribution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "github_contributions")
class GitHubContributionJpaEntity {

	@Id
	private String id;

	@Column(name = "user_id")
	private String userId;

	@Column(name = "github_user_id", nullable = false)
	private String githubUserId;

	@Column(name = "github_login", nullable = false, length = 100)
	private String githubLogin;

	@Column(nullable = false, length = 200)
	private String repository;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ContributionKind kind;

	@Column(name = "object_number", nullable = false)
	private int objectNumber;

	@Column(name = "object_id")
	private String objectId;

	@Column(nullable = false, length = 500)
	private String title;

	@Column(nullable = false, length = 500)
	private String url;

	@Column(nullable = false, length = 20)
	private String state;

	@Column(name = "opened_at")
	private Instant openedAt;

	@Column(name = "merged_at")
	private Instant mergedAt;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Column(name = "github_updated_at")
	private Instant githubUpdatedAt;

	@Column(name = "details_fetched", nullable = false)
	private boolean detailsFetched;

	@Column(nullable = false)
	private int additions;

	@Column(nullable = false)
	private int deletions;

	@Column(name = "changed_files", nullable = false)
	private int changedFiles;

	@Column(name = "files_json", columnDefinition = "longtext")
	private String filesJson;

	@Column(length = 1000)
	private String labels;

	@Column(name = "reviews_count", nullable = false)
	private int reviewsCount;

	@Column(name = "approvals_count", nullable = false)
	private int approvalsCount;

	@Column(name = "changes_requested_count", nullable = false)
	private int changesRequestedCount;

	@Column(name = "has_tests", nullable = false)
	private boolean hasTests;

	@Column(nullable = false)
	private boolean reverted;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private ContributionCategory category;

	@Enumerated(EnumType.STRING)
	@Column(name = "evaluation_status", nullable = false, length = 24)
	private ContributionStatus status;

	@Column(nullable = false)
	private int score;

	@Column(name = "signals_json", columnDefinition = "longtext")
	private String signalsJson;

	@Column(name = "review_note", length = 2000)
	private String reviewNote;

	@Column(name = "reviewed_by")
	private String reviewedBy;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected GitHubContributionJpaEntity() {
	}

	static GitHubContributionJpaEntity fromDomain(GitHubContribution c) {
		GitHubContributionJpaEntity e = new GitHubContributionJpaEntity();
		e.id = c.id();
		e.userId = c.userId();
		e.githubUserId = c.githubUserId();
		e.githubLogin = c.githubLogin();
		e.repository = c.repository();
		e.kind = c.kind();
		e.objectNumber = c.number();
		e.objectId = c.objectId();
		e.title = c.title();
		e.url = c.url();
		e.state = c.state();
		e.openedAt = c.openedAt();
		e.mergedAt = c.mergedAt();
		e.closedAt = c.closedAt();
		e.githubUpdatedAt = c.githubUpdatedAt();
		e.detailsFetched = c.detailsFetched();
		e.additions = c.additions();
		e.deletions = c.deletions();
		e.changedFiles = c.changedFiles();
		e.filesJson = c.filesJson();
		e.labels = c.labels();
		e.reviewsCount = c.reviewsCount();
		e.approvalsCount = c.approvalsCount();
		e.changesRequestedCount = c.changesRequestedCount();
		e.hasTests = c.hasTests();
		e.reverted = c.reverted();
		e.category = c.category();
		e.status = c.status();
		e.score = c.score();
		e.signalsJson = c.signalsJson();
		e.reviewNote = c.reviewNote();
		e.reviewedBy = c.reviewedBy();
		e.reviewedAt = c.reviewedAt();
		e.createdAt = c.createdAt();
		e.updatedAt = c.updatedAt();
		return e;
	}

	GitHubContribution toDomain() {
		return new GitHubContribution(id, userId, githubUserId, githubLogin, repository, kind, objectNumber,
			objectId, title, url, state, openedAt, mergedAt, closedAt, githubUpdatedAt, detailsFetched,
			additions, deletions, changedFiles, filesJson, labels, reviewsCount, approvalsCount,
			changesRequestedCount, hasTests, reverted, category, status, score, signalsJson, reviewNote,
			reviewedBy, reviewedAt, createdAt, updatedAt);
	}
}
