package com.socialcoisa.rede.media.adapters.out.persistence;

import java.util.List;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaPageCatalog;
import com.socialcoisa.rede.media.application.port.out.MediaRenderJobRepository;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaRenderJob;
import com.socialcoisa.rede.media.domain.MediaRenderJobStatus;

/**
 * Páginas de uma mídia: para PDF, as páginas renderizadas; para o resto, a própria mídia.
 *
 * <p>PDF sem render (pendente ou falho) e página já removida do storage devolvem lista vazia de
 * propósito. O catálogo do portfólio lê vazio como "ainda não dá para liberar" — o documento que
 * ninguém conseguiu olhar, ou que perdeu uma folha, não pode aparecer publicamente.</p>
 */
@Component
class MediaPageCatalogAdapter implements MediaPageCatalog {

	private static final String PDF_MIME = "application/pdf";

	private final MediaAssetRepository assets;
	private final MediaRenderJobRepository jobs;

	MediaPageCatalogAdapter(MediaAssetRepository assets, MediaRenderJobRepository jobs) {
		this.assets = assets;
		this.jobs = jobs;
	}

	@Override
	public List<PageRef> pagesOf(String mediaId) {
		if (mediaId == null || mediaId.isBlank()) {
			return List.of();
		}
		MediaAsset asset = assets.findById(mediaId).orElse(null);
		if (asset == null) {
			return List.of();
		}
		if (!PDF_MIME.equals(asset.mimeType())) {
			return List.of(new PageRef(asset.id(), asset.mimeType()));
		}
		MediaRenderJob job = jobs.find(asset.id()).orElse(null);
		if (job == null || job.status() != MediaRenderJobStatus.RENDERED) {
			return List.of();
		}
		List<PageRef> pages = new java.util.ArrayList<>();
		for (String key : job.pageKeyList()) {
			getPage(key).filter(MediaAsset::isVisible)
				.ifPresent(page -> pages.add(new PageRef(page.id(), page.mimeType())));
		}
		return List.copyOf(pages);
	}

	/**
	 * A página é o asset cuja chave é esta (`paginas/media_<lote>/pagina-<n>.png`): a chave é única e
	 * nasce com o asset, então procurar por ela não confunde uma página com outra.
	 */
	private java.util.Optional<MediaAsset> getPage(String objectKey) {
		return assets.findByObjectKey(objectKey);
	}
}
