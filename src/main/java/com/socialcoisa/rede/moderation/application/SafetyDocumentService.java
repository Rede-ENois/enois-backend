package com.socialcoisa.rede.moderation.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaPageCatalog;
import com.socialcoisa.rede.media.application.port.out.MediaPageCatalog.PageRef;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.moderation.application.SafetyPorts.AnalysedPieces;
import com.socialcoisa.rede.moderation.application.SafetyPorts.DocumentPieces;
import com.socialcoisa.rede.moderation.application.SafetyPorts.RetirablePieces;
import com.socialcoisa.rede.moderation.application.SafetyPorts.SafetyLedger;
import com.socialcoisa.rede.moderation.domain.MediaSafetyCheck;

/**
 * O documento inteiro na barreira: uma folha barrada leva o PDF e todas as folhas.
 *
 * <p>A retirada é pedida primeiro — a peça deixa de contar como peça e o documento para de ser
 * liberável mesmo que o worker de remoção demore ou falhe. O próprio documento também sai: o PDF
 * que perdeu uma folha não fica no storage servindo as folhas restantes por outro caminho.</p>
 */
@Component
class SafetyDocumentService implements DocumentPieces {

	private static final Logger LOG = LoggerFactory.getLogger(SafetyDocumentService.class);

	private final AnalysedPieces pieces;
	private final RetirablePieces withdrawals;
	private final MediaAssetRepository assets;
	private final MediaPageCatalog pages;
	private final SafetyLedger ledger;

	SafetyDocumentService(AnalysedPieces pieces, RetirablePieces withdrawals, MediaAssetRepository assets,
			MediaPageCatalog pages, SafetyLedger ledger) {
		this.pieces = pieces;
		this.withdrawals = withdrawals;
		this.assets = assets;
		this.pages = pages;
		this.ledger = ledger;
	}

	@Override
	public void retire(String mediaId) {
		Set<String> targets = new LinkedHashSet<>();
		targets.add(mediaId);
		pieces.piecesOf(mediaId).forEach(ref -> targets.add(ref.mediaId()));
		try {
			withdrawals.retire(List.copyOf(targets));
		}
		catch (RuntimeException failure) {
			LOG.warn("media_safety_retire_failed mediaId={} motivo={}", mediaId, failure.getClass().getSimpleName());
		}
	}

	/**
	 * Mídias do dono, recentes e ainda no ar, que precisam de decisão: imagem sem veredito, PDF com
	 * folha sem veredito, ou PDF cuja folha já está barrada no ledger e continua no ar.
	 *
	 * <p>A pergunta é feita pelo dono, não por uma lista do sistema: documento recém-desenhado tira
	 * carimbo novo a cada folha gravada, e uma lista ordenada por carimbo esconderia do lote justo o
	 * que acabou de entrar. Toda mídia precisa de dono, então nenhuma mídia fica fora da conta.</p>
	 */
	@Override
	public List<String> documentsNeedingCheck(String ownerId, Instant since, int limit) {
		int size = Math.max(1, limit);
		List<String> documents = new ArrayList<>();
		for (MediaAsset asset : assets.findVisibleByOwnerSince(ownerId, since, size * 4)) {
			if (documents.size() >= size) {
				break;
			}
			if (needsCheck(asset)) {
				documents.add(asset.id());
			}
		}
		return List.copyOf(documents);
	}

	/**
	 * A mídia precisa de decisão. Folha de PDF entra pela chave (`paginas/...`), que sozinha não leva
	 * a documento nenhum: quem precisa de decisão é o documento; a folha é o que ele tem de folha.
	 * Sem peça para olhar (PDF ainda desenhando) a resposta é sim, e o registro sem veredito é o que
	 * impede a liberação.
	 */
	private boolean needsCheck(MediaAsset asset) {
		if (asset.objectKey() != null && asset.objectKey().startsWith("paginas/")) {
			return false;
		}
		List<PageRef> pecas = pages.pagesOf(asset.id());
		if (pecas.isEmpty()) {
			return true;
		}
		Map<String, MediaSafetyCheck> checks = ledger.resultsOf(pecas.stream().map(PageRef::mediaId).toList());
		for (PageRef peca : pecas) {
			MediaSafetyCheck check = checks.get(peca.mediaId());
			if (check == null || (check.verdict().isViolation() && pecaStillUp(peca))) {
				return true;
			}
		}
		return false;
	}

	private boolean pecaStillUp(PageRef peca) {
		return assets.findById(peca.mediaId())
			.map(MediaAsset::isVisible)
			.orElse(false);
	}

	@Override
	public Instant createdAtOf(String pieceId) {
		return pieces.statusOf(pieceId).createdAt();
	}
}
