package com.socialcoisa.rede.moderation.adapters.out.media;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaDeletionJobRepository;
import com.socialcoisa.rede.media.application.port.out.MediaPageCatalog;
import com.socialcoisa.rede.media.application.port.out.MediaPageCatalog.PageRef;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.moderation.application.SafetyPorts.RetirablePieces;

/**
 * Retirada de mídia pela barreira de conteúdo: marca a peça como removida e pede o delete pelo
 * cadastro de mídia.
 *
 * <p>Não apaga objeto aqui. Quem apaga é o worker de remoção que já existe — purge de CDN e delete
 * do objeto, com retry e sem segurar conexão do pool. Pedir a remoção é o passo que tira a peça do
 * ar na hora: o catálogo do portfólio nega mídia removida, e a mídia com folha barrada deixa de ser
 * publicável. A peça que a barreira entrega é o documento; as páginas renderizadas dele vão junto,
 * porque são elas que foram olhadas e que continuam no bucket.</p>
 */
@Component
class MediaRetirementAdapter implements RetirablePieces {

	private static final Logger LOG = LoggerFactory.getLogger(MediaRetirementAdapter.class);

	private final MediaAssetRepository assets;
	private final MediaDeletionJobRepository deletions;
	private final MediaPageCatalog pages;
	private final Clock clock;

	MediaRetirementAdapter(MediaAssetRepository assets, MediaDeletionJobRepository deletions,
		MediaPageCatalog pages, Clock clock) {
		this.assets = assets;
		this.deletions = deletions;
		this.pages = pages;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void retire(List<String> pieceIds) {
		if (pieceIds == null || pieceIds.isEmpty()) {
			return;
		}
		List<MediaAsset> found = assets.findByIds(pieceIds);
		if (found.isEmpty()) {
			return;
		}
		Instant now = clock.instant();
		for (MediaAsset piece : found) {
			// As folhas primeiro: uma vez marcado como removido, o documento não tem mais página.
			for (PageRef page : pages.pagesOf(piece.id())) {
				if (!page.mediaId().equals(piece.id())) {
					assets.findById(page.mediaId()).ifPresent(folha -> request(folha, now));
				}
			}
			request(piece, now);
		}
		LOG.warn("media_safety_retired pecas={}", found.size());
	}

	private void request(MediaAsset piece, Instant now) {
		if (piece.requestDeletion(now)) {
			assets.save(piece);
		}
		deletions.request(newId("media_deletion_job"), piece.id(), null, piece.ownerId(), now);
	}

	private static String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}
}
