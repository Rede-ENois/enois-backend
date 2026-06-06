package com.socialcoisa.rede.media.domain;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.UserId;

public class MediaAsset {

	private final String id;
	private final UserId ownerId;
	private final String fileName;
	private final String mimeType;
	private final long size;
	private final String purpose;
	private final String objectKey;
	private final String cdnUrl;
	private final Instant createdAt;
	private final Instant expiresAt;
	private MediaStatus status;
	private MediaDeleteStatus deleteStatus;
	private Instant deletedAt;

	private MediaAsset(String id, UserId ownerId, String fileName, String mimeType, long size, String purpose,
			String objectKey, String cdnUrl, MediaStatus status, MediaDeleteStatus deleteStatus, Instant createdAt,
			Instant expiresAt, Instant deletedAt) {
		this.id = id;
		this.ownerId = ownerId;
		this.fileName = fileName;
		this.mimeType = mimeType;
		this.size = size;
		this.purpose = purpose;
		this.objectKey = objectKey;
		this.cdnUrl = cdnUrl;
		this.status = status;
		this.deleteStatus = deleteStatus;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.deletedAt = deletedAt;
	}

	public static MediaAsset pending(String id, UserId ownerId, String fileName, String mimeType, long size,
			String purpose, String objectKey, String cdnUrl, Instant createdAt, Instant expiresAt) {
		return new MediaAsset(id, ownerId, fileName, mimeType, size, purpose, objectKey, cdnUrl, MediaStatus.PENDING,
			MediaDeleteStatus.NONE, createdAt, expiresAt, null);
	}

	public static MediaAsset restore(String id, UserId ownerId, String fileName, String mimeType, long size,
			String purpose, String objectKey, String cdnUrl, MediaStatus status, MediaDeleteStatus deleteStatus,
			Instant createdAt, Instant expiresAt, Instant deletedAt) {
		return new MediaAsset(id, ownerId, fileName, mimeType, size, purpose, objectKey, cdnUrl, status, deleteStatus,
			createdAt, expiresAt, deletedAt);
	}

	public boolean belongsTo(UserId userId) {
		return ownerId.equals(userId);
	}

	public boolean requestDeletion(Instant deletedAt) {
		if (deleteStatus != MediaDeleteStatus.NONE && deleteStatus != MediaDeleteStatus.FAILED) {
			return false;
		}
		this.deleteStatus = MediaDeleteStatus.REQUESTED;
		this.deletedAt = deletedAt;
		return true;
	}

	public void markCdnPurged() {
		if (deleteStatus == MediaDeleteStatus.REQUESTED) {
			this.deleteStatus = MediaDeleteStatus.CDN_PURGED;
		}
	}

	public void markObjectDeleted(Instant deletedAt) {
		this.deleteStatus = MediaDeleteStatus.OBJECT_DELETED;
		this.deletedAt = deletedAt;
	}

	public void markDeletionFailed() {
		this.deleteStatus = MediaDeleteStatus.FAILED;
	}

	public String id() {
		return id;
	}

	public UserId ownerId() {
		return ownerId;
	}

	public String fileName() {
		return fileName;
	}

	public String mimeType() {
		return mimeType;
	}

	public long size() {
		return size;
	}

	public String purpose() {
		return purpose;
	}

	public String objectKey() {
		return objectKey;
	}

	public String cdnUrl() {
		return cdnUrl;
	}

	public MediaStatus status() {
		return status;
	}

	public MediaDeleteStatus deleteStatus() {
		return deleteStatus;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	public Instant deletedAt() {
		return deletedAt;
	}
}
