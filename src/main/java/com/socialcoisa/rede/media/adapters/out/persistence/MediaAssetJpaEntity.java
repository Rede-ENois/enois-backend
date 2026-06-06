package com.socialcoisa.rede.media.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaDeleteStatus;
import com.socialcoisa.rede.media.domain.MediaStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "media_assets", indexes = {
		@Index(name = "idx_media_assets_owner_created", columnList = "owner_id, created_at"),
		@Index(name = "idx_media_assets_delete_status", columnList = "delete_status") })
class MediaAssetJpaEntity {

	@Id
	private String id;

	@Column(name = "owner_id", nullable = false)
	private String ownerId;

	@Column(name = "file_name", nullable = false)
	private String fileName;

	@Column(name = "mime_type", nullable = false)
	private String mimeType;

	@Column(name = "size_bytes", nullable = false)
	private long sizeBytes;

	private String purpose;

	@Column(name = "object_key", nullable = false)
	private String objectKey;

	@Column(name = "cdn_url", nullable = false)
	private String cdnUrl;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MediaStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "delete_status", nullable = false)
	private MediaDeleteStatus deleteStatus;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected MediaAssetJpaEntity() {
	}

	static MediaAssetJpaEntity fromDomain(MediaAsset asset) {
		MediaAssetJpaEntity entity = new MediaAssetJpaEntity();
		entity.id = asset.id();
		entity.ownerId = asset.ownerId().value();
		entity.fileName = asset.fileName();
		entity.mimeType = asset.mimeType();
		entity.sizeBytes = asset.size();
		entity.purpose = asset.purpose();
		entity.objectKey = asset.objectKey();
		entity.cdnUrl = asset.cdnUrl();
		entity.status = asset.status();
		entity.deleteStatus = asset.deleteStatus();
		entity.createdAt = asset.createdAt();
		entity.expiresAt = asset.expiresAt();
		entity.deletedAt = asset.deletedAt();
		return entity;
	}

	MediaAsset toDomain() {
		return MediaAsset.restore(id, new UserId(ownerId), fileName, mimeType, sizeBytes, purpose, objectKey, cdnUrl,
			status, deleteStatus, createdAt, expiresAt, deletedAt);
	}
}
