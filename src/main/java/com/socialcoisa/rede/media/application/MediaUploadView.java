package com.socialcoisa.rede.media.application;

import java.time.Instant;

import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaDeleteStatus;
import com.socialcoisa.rede.media.domain.MediaStatus;

public record MediaUploadView(String id, String ownerId, String fileName, String mimeType, long size, String purpose,
		String objectKey, String cdnUrl, String uploadUrl, MediaStatus status, MediaDeleteStatus deleteStatus,
		Instant expiresAt) {

	public static MediaUploadView from(MediaAsset asset, String uploadUrl) {
		return new MediaUploadView(asset.id(), asset.ownerId().value(), asset.fileName(), asset.mimeType(), asset.size(),
			asset.purpose(), asset.objectKey(), asset.cdnUrl(), uploadUrl, asset.status(), asset.deleteStatus(),
			asset.expiresAt());
	}
}
