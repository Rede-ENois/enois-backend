package com.socialcoisa.rede.media.application;

import java.time.Instant;

import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaDeleteStatus;
import com.socialcoisa.rede.media.domain.MediaStatus;

public record MediaAssetView(String id, String ownerId, String fileName, String mimeType, long size, String purpose,
		String objectKey, String cdnUrl, MediaStatus status, MediaDeleteStatus deleteStatus, Instant createdAt,
		Instant expiresAt, Instant deletedAt) {

	public static MediaAssetView from(MediaAsset asset) {
		return new MediaAssetView(asset.id(), asset.ownerId().value(), asset.fileName(), asset.mimeType(), asset.size(),
			asset.purpose(), asset.objectKey(), asset.cdnUrl(), asset.status(), asset.deleteStatus(), asset.createdAt(),
			asset.expiresAt(), asset.deletedAt());
	}
}
