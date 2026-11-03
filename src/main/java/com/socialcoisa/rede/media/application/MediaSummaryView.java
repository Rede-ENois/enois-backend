package com.socialcoisa.rede.media.application;

import java.util.List;
import java.util.Map;

import com.socialcoisa.rede.media.domain.MediaAsset;

/**
 * What a card needs to draw an attachment without fetching it: the address, the type and the pixel
 * size (null when unknown: non-images or images not measured yet).
 */
public record MediaSummaryView(String id, String cdnUrl, String mimeType, Integer width, Integer height) {

	public static MediaSummaryView from(MediaAsset asset) {
		return new MediaSummaryView(asset.id(), asset.cdnUrl(), asset.mimeType(), asset.width(), asset.height());
	}

	/** The attachments in the order of {@code mediaIds}, skipping the ones missing from {@code byId}. */
	public static List<MediaSummaryView> inOrder(List<String> mediaIds, Map<String, MediaSummaryView> byId) {
		if (mediaIds == null || mediaIds.isEmpty() || byId.isEmpty()) {
			return List.of();
		}
		return mediaIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
	}
}
