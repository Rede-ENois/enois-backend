package com.socialcoisa.rede.network.application;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.network.application.port.out.ContentVersionRepository;

/**
 * Varredura periódica da reconciliação de conteúdo (fase 3).
 *
 * <p>O alerta {@code SYNC_DIVERGENCE} existia, mas só nascia quando o nó pedia a reconciliação. Um
 * nó que nunca pede nunca era conferido. Esta varredura confere o que o Control Plane já registrou
 * de cada nó, sem depender de o nó cooperar.</p>
 *
 * <p>O laço vive aqui, e não no serviço, porque cada item precisa da própria transação: uma origem
 * que não existe mais derruba a leitura dela, e a varredura inteira numa transação só perderia os
 * outros nós da rodada. O worker trata o desfecho de cada item e segue.</p>
 *
 * <p>Desligável por propriedade para a suíte chamar {@link #runOnce()} na mão, como o resto dos
 * workers do projeto.</p>
 */
@Component
public class ContentReconcileWorker {

	private static final Logger LOG = LoggerFactory.getLogger(ContentReconcileWorker.class);

	private final ContentReconcileService reconcile;
	private final boolean enabled;
	private final int batchSize;

	ContentReconcileWorker(ContentReconcileService reconcile,
			@Value("${rede.network.content.reconcile.enabled:true}") boolean enabled,
			@Value("${rede.network.content.reconcile.batch-size:200}") int batchSize) {
		this.reconcile = reconcile;
		this.enabled = enabled;
		this.batchSize = Math.max(1, batchSize);
	}

	@Scheduled(initialDelayString = "${rede.network.content.reconcile.initial-delay-ms:120000}",
			fixedDelayString = "${rede.network.content.reconcile.delay-ms:300000}")
	void sweep() {
		if (!enabled) {
			return;
		}
		runOnce();
	}

	/**
	 * Uma rodada: confere o lote da fila e devolve quantos divergiram.
	 *
	 * <p>O lote é limitado de propósito. A fila é ordenada por "nunca conferido, depois o mais
	 * antigo", então rodadas sucessivas cobrem tudo sem que uma rodada segure o banco por minutos
	 * num nó com milhares de agregados.</p>
	 */
	public int runOnce() {
		List<ContentVersionRepository.NodeSnapshot> due = reconcile.due(batchSize);
		int divergent = 0;
		for (ContentVersionRepository.NodeSnapshot snapshot : due) {
			try {
				if (reconcile.check(snapshot).isPresent()) {
					divergent++;
				}
			}
			catch (RuntimeException failure) {
				// Um item não pode parar a rodada: o próximo ciclo tenta de novo, e a fila ordena
				// pelo mais antigo, então o item problemático não fica no fim para sempre.
				LOG.warn("Reconciliacao de conteudo falhou node={} kind={} id={}", snapshot.nodeId(),
					snapshot.kind(), snapshot.contentId(), failure);
			}
		}
		if (divergent > 0) {
			LOG.info("Reconciliacao de conteudo: {} de {} snapshots divergentes", divergent, due.size());
		}
		return divergent;
	}
}
