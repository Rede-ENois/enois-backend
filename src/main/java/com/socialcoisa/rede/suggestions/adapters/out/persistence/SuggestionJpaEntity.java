package com.socialcoisa.rede.suggestions.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import com.socialcoisa.rede.suggestions.domain.Suggestion;
import com.socialcoisa.rede.suggestions.domain.SuggestionStatus;
import com.socialcoisa.rede.suggestions.domain.SuggestionTarget;

@Entity
@Table(name = "suggestions", indexes = {
		@Index(name = "idx_suggestions_status_created", columnList = "status, created_at"),
		@Index(name = "idx_suggestions_user_created", columnList = "user_id, created_at") })
class SuggestionJpaEntity {

	@Id
	private String id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 24)
	private SuggestionTarget target;

	@Column(nullable = false, length = 120)
	private String title;

	@Column(nullable = false, length = 4000)
	private String body;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private SuggestionStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "reviewed_by")
	private String reviewedBy;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	@Column(name = "github_issue_number")
	private Integer githubIssueNumber;

	@Column(name = "github_issue_url", length = 255)
	private String githubIssueUrl;

	protected SuggestionJpaEntity() {
	}

	static SuggestionJpaEntity fromDomain(Suggestion suggestion) {
		SuggestionJpaEntity entity = new SuggestionJpaEntity();
		entity.id = suggestion.id();
		entity.userId = suggestion.userId();
		entity.target = suggestion.target();
		entity.title = suggestion.title();
		entity.body = suggestion.body();
		entity.status = suggestion.status();
		entity.createdAt = suggestion.createdAt();
		entity.reviewedBy = suggestion.reviewedBy();
		entity.reviewedAt = suggestion.reviewedAt();
		entity.githubIssueNumber = suggestion.githubIssueNumber();
		entity.githubIssueUrl = suggestion.githubIssueUrl();
		return entity;
	}

	Suggestion toDomain() {
		return new Suggestion(id, userId, target, title, body, status, createdAt, reviewedBy, reviewedAt,
			githubIssueNumber, githubIssueUrl);
	}
}
