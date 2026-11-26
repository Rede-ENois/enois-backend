package com.socialcoisa.rede.portfolio.adapters.out.catalogs;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaStatus;
import com.socialcoisa.rede.portfolio.application.port.out.PortfolioMediaCatalog;
import com.socialcoisa.rede.portfolio.domain.PortfolioBlockType;

/** Mídia de bloco: precisa existir, estar pronta, ser do dono e caber no tipo do bloco. */
@Component
class MediaAssetPortfolioMediaAdapter implements PortfolioMediaCatalog {

	private static final String PDF_MIME = "application/pdf";

	private final MediaAssetRepository mediaAssets;

	MediaAssetPortfolioMediaAdapter(MediaAssetRepository mediaAssets) {
		this.mediaAssets = mediaAssets;
	}

	@Override
	public boolean usable(String userId, String mediaId, PortfolioBlockType type) {
		return mediaAssets.findById(mediaId)
			.filter(MediaAsset::isVisible)
			.filter(asset -> asset.status() == MediaStatus.READY)
			.filter(asset -> asset.ownerId().value().equals(userId))
			.map(MediaAsset::mimeType)
			.map(mime -> PDF_MIME.equals(mime) == (type == PortfolioBlockType.PDF))
			.orElse(false);
	}
}
