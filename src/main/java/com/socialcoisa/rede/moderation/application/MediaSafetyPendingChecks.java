package com.socialcoisa.rede.moderation.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.media.application.port.out.MediaPageCatalog.PageRef;
import com.socialcoisa.rede.media.application.port.out.MediaPageSets;
import com.socialcoisa.rede.moderation.application.SafetyPorts.AnalysedPieces;
import com.socialcoisa.rede.moderation.application.SafetyPorts.DocumentPieces;
import com.socialcoisa.rede.moderation.application.SafetyPorts.SafetyLedger;
import com.socialcoisa.rede.moderation.domain.MediaSafetyCheck;
import com.socialcoisa.rede.moderation.domain.MediaSafetyVerdict;

/**
 * O que a moderação de mídia diz sobre uma mídia: se ela pode ir ao ar e se falta algo olhar.
 *
 * <p>Para imagem, a peça que o classificador olha é o próprio arquivo. Para PDF, são as páginas
 * renderizadas, uma por uma — o arquivo nunca é olhado pelo classificador. Este componente guarda a
 * decisão no MESMO ledger da checagem de imagem e responde as perguntas que o catálogo do portfólio
 * e a varredura fazem: "as páginas desta mídia estão liberadas?", "o que ainda não foi olhado?"</p>
 *
 * <p>Some do ar, nas três pontas, o documento com página explícita: a mídia e as páginas recebem o
 * pedido de remoção, o catálogo do portfólio nega a peça e a publicação, e o resultado fica no
 * ledger como prova auditável.</p>
 *
 * <p><b>Um caminho só para a análise:</b> o render (na subida ou na varredura) só PEDE aqui a
 * checagem do documento; quem manda analisar é o serviço de segurança, que é dono da decisão. A
 * fila junta os pedidos das duas pontas, e o ledger impede que a mesma página seja olhada duas
 * vezes.</p>
 */
@Component
public class MediaSafetyPendingChecks implements MediaPageSets {

	private static final Logger LOG = LoggerFactory.getLogger(MediaSafetyPendingChecks.class);

	private final SafetyLedger ledger;
	private final AnalysedPieces pieces;
	private final DocumentPieces documents;
	/** Mídias pedidas para análise; quem as analisa é o serviço de segurança. */
	private final Set<String> pendingAnalysis = new LinkedHashSet<>();
	/**
	 * O serviço de segurança, injetado só na hora de usar: ele depende deste componente (é quem
	 * responde "a mídia ainda está aberta?"), e a dependência de volta fecharia um ciclo de criação.
	 */
	private MediaSafetyService analyser;

	MediaSafetyPendingChecks(SafetyLedger ledger, AnalysedPieces pieces, DocumentPieces documents) {
		this.ledger = ledger;
		this.pieces = pieces;
		this.documents = documents;
	}

	@Autowired
	@Lazy
	void analyser(MediaSafetyService analyser) {
		this.analyser = analyser;
	}


	@Override
	public void checkSoon(String mediaId) {
		if (mediaId == null) {
			return;
		}
		synchronized (pendingAnalysis) {
			pendingAnalysis.add(mediaId);
		}
	}

	/** A fila vista de fora, com a trava: quem espera a análise terminar lê pelas mesmas regras. */
	@Override
	public Set<String> waitingNow() {
		synchronized (pendingAnalysis) {
			return Set.copyOf(pendingAnalysis);
		}
	}

	/** Mídias pedidas e ainda não analisadas (para o teste que espera a subida terminar). */
	public boolean waiting(String mediaId) {
		synchronized (pendingAnalysis) {
			return pendingAnalysis.contains(mediaId);
		}
	}

	@Override
	public int analyseQueued() {
		List<String> queued;
		synchronized (pendingAnalysis) {
			queued = List.copyOf(pendingAnalysis);
			pendingAnalysis.clear();
		}
		int enforced = 0;
		for (String mediaId : new LinkedHashSet<>(queued)) {
			// Uma mídia que estraga a rodada não pode segurar as outras da fila: folha explícita de
			// um documento é motivo de saída da fila, não de parada.
			try {
				if ("ENFORCED".equals(analyser.check(mediaId).status())) {
					enforced++;
				}
			}
			catch (RuntimeException failure) {
				LOG.warn("media_safety_analysis_failed mediaId={} motivo={}", mediaId,
					failure.getClass().getSimpleName());
			}
		}
		return enforced;
	}

	@Override
	public List<String> uncheckedOf(String mediaId) {
		List<PageRef> refs = pieces.piecesOf(mediaId);
		if (refs.isEmpty()) {
			// Sem peça renderizada não há veredito possível: a mídia não é liberável.
			return List.of(mediaId);
		}
		List<String> ids = refs.stream().map(PageRef::mediaId).toList();
		Map<String, MediaSafetyCheck> results = pieces.resultsOf(ids);
		List<String> missing = new ArrayList<>();
		for (String id : ids) {
			MediaSafetyCheck result = results.get(id);
			if (result == null || result.verdict() != MediaSafetyVerdict.SAFE) {
				missing.add(id);
			}
		}
		return missing;
	}

	@Override
	public void retire(String mediaId) {
		documents.retire(mediaId);
	}
}
