package com.socialcoisa.rede.contributions.domain;

import java.time.Instant;

/**
 * Evidência persistente de uma contribuição externa (PR integrado ou issue).
 *
 * <p>A contagem que concede selo é sempre recalculada a partir destas linhas: a mesma contribuição
 * não conta duas vezes (chave única por repositório, tipo e número) e o que foi revertido ou
 * invalidado deixa de somar. Notas e estados internos de revisão não aparecem no perfil público.</p>
 */
public record GitHubContribution(String id, String userId, String githubUserId, String githubLogin,
		String repository, ContributionKind kind, int number, String objectId, String title, String url,
		String state, Instant openedAt, Instant mergedAt, Instant closedAt, Instant githubUpdatedAt,
		boolean detailsFetched, int additions, int deletions, int changedFiles, String filesJson,
		String labels, int reviewsCount, int approvalsCount, int changesRequestedCount, boolean hasTests,
		boolean reverted, ContributionCategory category, ContributionStatus status, int score,
		String signalsJson, String reviewNote, String reviewedBy, Instant reviewedAt, Instant createdAt,
		Instant updatedAt) {

	/** Só evidência validada e não revertida entra na contagem do selo. */
	public boolean countable() {
		return status == ContributionStatus.VALIDATED && !reverted;
	}

	public GitHubContribution withUserId(String newUserId) {
		return builder().from(this).userId(newUserId).build();
	}

	public GitHubContribution withEvidence(String newUserId, String newTitle, String newUrl,
			String newState, Instant newOpenedAt, Instant newMergedAt, Instant newGithubUpdatedAt,
			boolean newDetailsFetched, int newAdditions, int newDeletions, int newChangedFiles,
			String newFilesJson, int newReviewsCount, int newApprovalsCount,
			int newChangesRequestedCount, boolean newHasTests, int newScore, String newSignalsJson,
			ContributionStatus newStatus, Instant now) {
		return builder().from(this).userId(newUserId).title(newTitle).url(newUrl).state(newState)
			.openedAt(newOpenedAt).mergedAt(newMergedAt).githubUpdatedAt(newGithubUpdatedAt)
			.detailsFetched(newDetailsFetched).additions(newAdditions).deletions(newDeletions)
			.changedFiles(newChangedFiles).filesJson(newFilesJson).reviewsCount(newReviewsCount)
			.approvalsCount(newApprovalsCount).changesRequestedCount(newChangesRequestedCount)
			.hasTests(newHasTests).score(newScore).signalsJson(newSignalsJson).status(newStatus)
			.updatedAt(now).build();
	}

	public GitHubContribution withIssueEvidence(String newUserId, String newTitle, String newUrl,
			String newState, Instant newOpenedAt, Instant newClosedAt, Instant newGithubUpdatedAt,
			String newLabels, ContributionStatus newStatus, Instant now) {
		return builder().from(this).userId(newUserId).title(newTitle).url(newUrl).state(newState)
			.openedAt(newOpenedAt).closedAt(newClosedAt).githubUpdatedAt(newGithubUpdatedAt)
			.labels(newLabels).status(newStatus).updatedAt(now).build();
	}

	public GitHubContribution withReverted(boolean newReverted, Instant now) {
		return builder().from(this).reverted(newReverted).updatedAt(now).build();
	}

	public GitHubContribution withReview(ContributionStatus newStatus, String note, String by, Instant at) {
		return builder().from(this).status(newStatus).reviewNote(note).reviewedBy(by).reviewedAt(at)
			.updatedAt(at).build();
	}

	public GitHubContribution withSignals(int newScore, String newSignals, Instant now) {
		return builder().from(this).score(newScore).signalsJson(newSignals).updatedAt(now).build();
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private String id;
		private String userId;
		private String githubUserId;
		private String githubLogin;
		private String repository;
		private ContributionKind kind;
		private int number;
		private String objectId;
		private String title;
		private String url;
		private String state;
		private Instant openedAt;
		private Instant mergedAt;
		private Instant closedAt;
		private Instant githubUpdatedAt;
		private boolean detailsFetched;
		private int additions;
		private int deletions;
		private int changedFiles;
		private String filesJson;
		private String labels;
		private int reviewsCount;
		private int approvalsCount;
		private int changesRequestedCount;
		private boolean hasTests;
		private boolean reverted;
		private ContributionCategory category;
		private ContributionStatus status;
		private int score;
		private String signalsJson;
		private String reviewNote;
		private String reviewedBy;
		private Instant reviewedAt;
		private Instant createdAt;
		private Instant updatedAt;

		public Builder from(GitHubContribution c) {
			this.id = c.id;
			this.userId = c.userId;
			this.githubUserId = c.githubUserId;
			this.githubLogin = c.githubLogin;
			this.repository = c.repository;
			this.kind = c.kind;
			this.number = c.number;
			this.objectId = c.objectId;
			this.title = c.title;
			this.url = c.url;
			this.state = c.state;
			this.openedAt = c.openedAt;
			this.mergedAt = c.mergedAt;
			this.closedAt = c.closedAt;
			this.githubUpdatedAt = c.githubUpdatedAt;
			this.detailsFetched = c.detailsFetched;
			this.additions = c.additions;
			this.deletions = c.deletions;
			this.changedFiles = c.changedFiles;
			this.filesJson = c.filesJson;
			this.labels = c.labels;
			this.reviewsCount = c.reviewsCount;
			this.approvalsCount = c.approvalsCount;
			this.changesRequestedCount = c.changesRequestedCount;
			this.hasTests = c.hasTests;
			this.reverted = c.reverted;
			this.category = c.category;
			this.status = c.status;
			this.score = c.score;
			this.signalsJson = c.signalsJson;
			this.reviewNote = c.reviewNote;
			this.reviewedBy = c.reviewedBy;
			this.reviewedAt = c.reviewedAt;
			this.createdAt = c.createdAt;
			this.updatedAt = c.updatedAt;
			return this;
		}

		public Builder id(String value) { this.id = value; return this; }
		public Builder userId(String value) { this.userId = value; return this; }
		public Builder githubUserId(String value) { this.githubUserId = value; return this; }
		public Builder githubLogin(String value) { this.githubLogin = value; return this; }
		public Builder repository(String value) { this.repository = value; return this; }
		public Builder kind(ContributionKind value) { this.kind = value; return this; }
		public Builder number(int value) { this.number = value; return this; }
		public Builder objectId(String value) { this.objectId = value; return this; }
		public Builder title(String value) { this.title = value; return this; }
		public Builder url(String value) { this.url = value; return this; }
		public Builder state(String value) { this.state = value; return this; }
		public Builder openedAt(Instant value) { this.openedAt = value; return this; }
		public Builder mergedAt(Instant value) { this.mergedAt = value; return this; }
		public Builder closedAt(Instant value) { this.closedAt = value; return this; }
		public Builder githubUpdatedAt(Instant value) { this.githubUpdatedAt = value; return this; }
		public Builder detailsFetched(boolean value) { this.detailsFetched = value; return this; }
		public Builder additions(int value) { this.additions = value; return this; }
		public Builder deletions(int value) { this.deletions = value; return this; }
		public Builder changedFiles(int value) { this.changedFiles = value; return this; }
		public Builder filesJson(String value) { this.filesJson = value; return this; }
		public Builder labels(String value) { this.labels = value; return this; }
		public Builder reviewsCount(int value) { this.reviewsCount = value; return this; }
		public Builder approvalsCount(int value) { this.approvalsCount = value; return this; }
		public Builder changesRequestedCount(int value) { this.changesRequestedCount = value; return this; }
		public Builder hasTests(boolean value) { this.hasTests = value; return this; }
		public Builder reverted(boolean value) { this.reverted = value; return this; }
		public Builder category(ContributionCategory value) { this.category = value; return this; }
		public Builder status(ContributionStatus value) { this.status = value; return this; }
		public Builder score(int value) { this.score = value; return this; }
		public Builder signalsJson(String value) { this.signalsJson = value; return this; }
		public Builder reviewNote(String value) { this.reviewNote = value; return this; }
		public Builder reviewedBy(String value) { this.reviewedBy = value; return this; }
		public Builder reviewedAt(Instant value) { this.reviewedAt = value; return this; }
		public Builder createdAt(Instant value) { this.createdAt = value; return this; }
		public Builder updatedAt(Instant value) { this.updatedAt = value; return this; }

		public GitHubContribution build() {
			return new GitHubContribution(id, userId, githubUserId, githubLogin, repository, kind, number,
				objectId, title, url, state, openedAt, mergedAt, closedAt, githubUpdatedAt, detailsFetched,
				additions, deletions, changedFiles, filesJson, labels, reviewsCount, approvalsCount,
				changesRequestedCount, hasTests, reverted, category, status, score, signalsJson, reviewNote,
				reviewedBy, reviewedAt, createdAt, updatedAt);
		}
	}
}
