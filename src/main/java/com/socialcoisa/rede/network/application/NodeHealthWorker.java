package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * Devolve ao balanceamento os nós cuja janela de remoção venceu (fase 3).
 *
 * <p>A remoção temporária só é temporária se alguém a desfizer. O filtro do scheduler já ignora a
 * janela vencida — o nó volta a receber trabalho sozinho —, mas a linha continuaria dizendo
 * "removido até ontem" e o streak de falha continuaria no limite: a próxima falha o tiraria de novo
 * na hora, sem chance de mostrar que melhorou. Esta varredura limpa os dois e audita a volta, para o
 * painel contar a história inteira (saiu, voltou).</p>
 *
 * <p>Desligável por propriedade para a suíte chamar {@link #runOnce()} na mão, como os demais
 * workers do projeto.</p>
 */
@Component
public class NodeHealthWorker {

	private static final Logger LOG = LoggerFactory.getLogger(NodeHealthWorker.class);

	private final NetworkNodeRepository nodes;
	private final AuditTrail audit;
	private final Clock clock;
	private final boolean enabled;
	private final int batchSize;

	NodeHealthWorker(NetworkNodeRepository nodes, AuditTrail audit, Clock clock,
			@Value("${rede.network.balancing.recovery.enabled:true}") boolean enabled,
			@Value("${rede.network.balancing.recovery.batch-size:200}") int batchSize) {
		this.nodes = nodes;
		this.audit = audit;
		this.clock = clock;
		this.enabled = enabled;
		this.batchSize = Math.max(1, batchSize);
	}

	@Scheduled(initialDelayString = "${rede.network.balancing.recovery.initial-delay-ms:60000}",
			fixedDelayString = "${rede.network.balancing.recovery.delay-ms:60000}")
	void sweep() {
		if (!enabled) {
			return;
		}
		runOnce();
	}

	/**
	 * Uma rodada: devolve ao balanceamento quem já cumpriu a janela.
	 *
	 * @return quantos nós voltaram
	 */
	@Transactional
	public int runOnce() {
		Instant now = clock.instant();
		List<NetworkNode> expired = nodes.findQuarantineExpired(now, batchSize);
		int released = 0;
		for (NetworkNode node : expired) {
			nodes.release(node.id());
			released++;
			audit.record("system", "NETWORK_NODE_RELEASED", node.id(),
				"{\"reason\":\"" + node.quarantineReason() + "\",\"quarantinedUntil\":\"" + node.quarantinedUntil()
					+ "\"}");
			LOG.info("No {} voltou ao balanceamento (janela vencida em {})", node.id(), node.quarantinedUntil());
		}
		return released;
	}
}
