package com.socialcoisa.rede.media.application;

import java.time.Instant;

import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaDeleteStatus;
import com.socialcoisa.rede.media.domain.MediaStatus;

public record MediaAssetView(String id, String ownerId, String fileName, String mimeType, long size, String purpose,
		String objectKey, String cdnUrl, MediaStatus status, MediaDeleteStatus deleteStatus, Instant createdAt,
		Instant expiresAt, Instant deletedAt, Integer width, Integer height,
		/** Onde o objeto está de fato: sem isso o dono não sabia se o arquivo já subiu ao S3 ou não. */
		String storageProvider, /** Origem do vídeo (quando há transcode): cópia da própria pessoa, não conteúdo público. */
		boolean originalSource) {

	public static MediaAssetView from(MediaAsset asset) {
		return new MediaAssetView(asset.id(), asset.ownerId().value(), asset.fileName(), asset.mimeType(), asset.size(),
			asset.purpose(), asset.objectKey(), asset.cdnUrl(), asset.status(), asset.deleteStatus(), asset.createdAt(),
			asset.expiresAt(), asset.deletedAt(), asset.width(), asset.height(),
			asset.storageProvider() == null ? null : asset.storageProvider().name(), isOriginalSource(asset));
	}

	/** Vídeo fora do caminho da denúncia: o `purpose` dele é o do próprio arquivo, não o de captura. */
	private static boolean isOriginalSource(MediaAsset asset) {
		return MediaService.REPORT_CAPTURE_PURPOSE.equals(asset.purpose());
	}
}
