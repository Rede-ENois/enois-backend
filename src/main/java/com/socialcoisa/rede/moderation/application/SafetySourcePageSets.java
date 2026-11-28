package com.socialcoisa.rede.moderation.application;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaPageCatalog;
import com.socialcoisa.rede.media.application.port.out.MediaPageCatalog.PageRef;
import com.socialcoisa.rede.media.application.port.out.MediaRenderJobRepository;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaRenderJob;
import com.socialcoisa.rede.moderation.application.SafetyPorts.PieceState;
import com.socialcoisa.rede.moderation.application.SafetyPorts.PieceStatus;
import com.socialcoisa.rede.moderation.application.port.out.MediaSafetyCheckRepository;
import com.socialcoisa.rede.moderation.domain.MediaSafetyCheck;

/**
 * A mídia como o classificador enxerga: imagem é o próprio arquivo; PDF são as páginas renderizadas,
 * uma por uma. Aqui ficam as respostas que faltavam nas duas pontas do pipeline — quais peças
 * existem, o que cada uma já decidiu e em que estado ela está, e onde cada resultado é gravado —
 * sem que o aparelho de checagem precise saber que existe PDF no produto.
 */
@Component
class SafetySourcePageSets implements SafetyPorts.AnalysedPieces, SafetyPorts.SafetyLedger {

	private final MediaPageCatalog pages;
	private final MediaSafetyCheckRepository checks;
	private final MediaAssetRepository assets;
	private final MediaRenderJobRepository jobs;

	SafetySourcePageSets(MediaPageCatalog pages, MediaSafetyCheckRepository checks, MediaAssetRepository assets,
			MediaRenderJobRepository jobs) {
		this.pages = pages;
		this.checks = checks;
		this.assets = assets;
		this.jobs = jobs;
	}

	@Override
	public List<PageRef> piecesOf(String mediaId) {
		return pages.pagesOf(mediaId);
	}

	@Override
	public java.util.Optional<String> documentOf(String pieceId) {
		return assets.findById(pieceId)
			.filter(piece -> piece.objectKey() != null && piece.objectKey().startsWith("paginas/"))
			.flatMap(piece -> jobs.documentOfPageKey(piece.objectKey()))
			.map(MediaRenderJob::mediaId);
	}

	@Override
	public Map<String, MediaSafetyCheck> resultsOf(List<String> pieceIds) {
		Map<String, MediaSafetyCheck> results = new LinkedHashMap<>();
		if (pieceIds == null) {
			return results;
		}
		for (String pieceId : pieceIds) {
			checks.findById(pieceId).ifPresent(check -> results.put(pieceId, check));
		}
		return results;
	}

	@Override
	public PieceStatus statusOf(String pieceId) {
		MediaAsset asset = assets.findById(pieceId).orElse(null);
		if (asset == null) {
			return PieceStatus.missing();
		}
		return new PieceStatus(asset.isVisible() ? PieceState.OPEN : PieceState.WITHDRAWN, asset.createdAt());
	}

	@Override
	public String ownerOf(String mediaId) {
		return assets.findById(mediaId).map(asset -> asset.ownerId().value()).orElse(null);
	}

	@Override
	public void save(MediaSafetyCheck check) {
		checks.save(check);
	}

	/**
	 * Peças entregues ao classificador que não gravaram linha: a revisão humana decide a partir daí.
	 * A folha de documento retirado sai da fila junto com o documento — ele já foi decidido.
	 */
	@Override
	public List<String> findUncheckedPieces(int limit) {
		int size = Math.max(1, limit);
		Set<String> retiradas = new HashSet<>();
		for (MediaRenderJob job : jobs.findWithPages(size)) {
			assets.findById(job.mediaId())
				.filter(asset -> !asset.isVisible())
				.ifPresent(asset -> retiradas.addAll(job.pageKeyList()));
		}
		return checks.findUncheckedMediaIdsSince(java.time.Instant.EPOCH, size).stream()
			.filter(mediaId -> !retiradas.contains(assets.findById(mediaId).map(MediaAsset::objectKey).orElse(null)))
			.toList();
	}
}
