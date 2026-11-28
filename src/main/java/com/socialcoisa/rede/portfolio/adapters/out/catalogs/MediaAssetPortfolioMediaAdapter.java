package com.socialcoisa.rede.portfolio.adapters.out.catalogs;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaPageSets;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.portfolio.application.port.out.PortfolioMediaCatalog;
import com.socialcoisa.rede.portfolio.domain.PortfolioBlockType;

/**
 * Mídia de bloco: precisa existir, ser do dono, caber no tipo do bloco e estar APROVADA pela
 * checagem de conteúdo.
 *
 * <p>A aprovação não se lê no asset: para PDF ela está nas páginas renderizadas, para imagem no
 * veredito da própria mídia. O {@link MediaPageSets} responde isso pelo ledger. Mídia sem página
 * (PDF que não renderizou), página sem veredito e página já retirada ficam fora — a peça não publica
 * o que ninguém olhou, nem documento que perdeu uma folha no takedown.</p>
 */
@Component
class MediaAssetPortfolioMediaAdapter implements PortfolioMediaCatalog {

	private static final String PDF_MIME = "application/pdf";

	private final MediaAssetRepository mediaAssets;
	private final MediaPageSets pages;

	MediaAssetPortfolioMediaAdapter(MediaAssetRepository mediaAssets, MediaPageSets pages) {
		this.mediaAssets = mediaAssets;
		this.pages = pages;
	}

	@Override
	public boolean usable(String userId, String mediaId, PortfolioBlockType type) {
		return mediaAssets.findById(mediaId)
			.filter(MediaAsset::isVisible)
			.filter(asset -> asset.ownerId().value().equals(userId))
			.filter(asset -> PDF_MIME.equals(asset.mimeType()) == (type == PortfolioBlockType.PDF))
			.filter(asset -> pages.uncheckedOf(asset.id()).isEmpty())
			.isPresent();
	}

	@Override
	public Map<String, List<String>> uncheckedMedia(Collection<String> mediaIds) {
		Map<String, List<String>> report = new LinkedHashMap<>();
		if (mediaIds == null) {
			return report;
		}
		for (String mediaId : mediaIds) {
			List<String> missing = pages.uncheckedOf(mediaId);
			if (!missing.isEmpty()) {
				report.put(mediaId, missing);
			}
		}
		return report;
	}
}
