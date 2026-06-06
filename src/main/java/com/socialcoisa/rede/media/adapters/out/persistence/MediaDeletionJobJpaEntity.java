package com.socialcoisa.rede.media.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.domain.MediaDeletionJob;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "media_deletion_jobs", indexes = @Index(name = "idx_media_deletion_jobs_status_next",
		columnList = "status, next_attempt_at"))
class MediaDeletionJobJpaEntity {

	@Id
	private String id;

	@Column(name = "media_id", nullable = false)
	private String mediaId;

	@Column(name = "post_id")
	private String postId;

	@Column(name = "owner_id", nullable = false)
	private String ownerId;

	@Column(nullable = false)
	private String status;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(name = "processed_at")
	private Instant processedAt;

	@Column(name = "error_message")
	private String errorMessage;

	protected MediaDeletionJobJpaEntity() {
	}

	MediaDeletionJobJpaEntity(String id, String mediaId, String postId, String ownerId, Instant createdAt) {
		this.id = id;
		this.mediaId = mediaId;
		this.postId = postId;
		this.ownerId = ownerId;
		this.status = "REQUESTED";
		this.attempts = 0;
		this.createdAt = createdAt;
		this.nextAttemptAt = createdAt;
	}

	MediaDeletionJob toDomain() {
		return new MediaDeletionJob(id, mediaId, postId == null ? null : new PostId(postId), new UserId(ownerId), status,
			attempts, createdAt, nextAttemptAt);
	}

	String status() {
		return status;
	}

	void complete(Instant processedAt) {
		this.status = "COMPLETED";
		this.processedAt = processedAt;
		this.errorMessage = null;
	}

	void fail(String errorMessage, Instant nextAttemptAt) {
		this.status = "REQUESTED";
		this.attempts += 1;
		this.nextAttemptAt = nextAttemptAt;
		this.errorMessage = errorMessage;
	}
}
