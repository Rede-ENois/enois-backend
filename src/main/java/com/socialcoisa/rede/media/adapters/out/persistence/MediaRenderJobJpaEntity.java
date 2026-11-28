package com.socialcoisa.rede.media.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.media.domain.MediaRenderJob;
import com.socialcoisa.rede.media.domain.MediaRenderJobStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "media_render_jobs")
class MediaRenderJobJpaEntity {

	@Id
	@Column(name = "media_id")
	private String mediaId;

	@Column(name = "owner_id", nullable = false)
	private String ownerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MediaRenderJobStatus status;

	@Column(nullable = false)
	private int attempt;

	@Column(nullable = false)
	private int pages;

	@Column(name = "page_keys", length = 2000)
	private String pageKeys;

	@Column(name = "last_error", length = 300)
	private String lastError;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected MediaRenderJobJpaEntity() {
	}

	static MediaRenderJobJpaEntity fromDomain(MediaRenderJob job) {
		MediaRenderJobJpaEntity entity = new MediaRenderJobJpaEntity();
		entity.mediaId = job.mediaId();
		entity.ownerId = job.ownerId();
		entity.status = job.status();
		entity.attempt = job.attempt();
		entity.pages = job.pages();
		entity.pageKeys = job.pageKeys();
		entity.lastError = job.lastError();
		entity.createdAt = job.createdAt();
		entity.updatedAt = job.updatedAt();
		return entity;
	}

	MediaRenderJob toDomain() {
		return new MediaRenderJob(mediaId, ownerId, status, attempt, pages, pageKeys, lastError, createdAt,
			updatedAt);
	}
}
