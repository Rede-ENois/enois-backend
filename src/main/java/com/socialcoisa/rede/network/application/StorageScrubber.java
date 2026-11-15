package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * Varredura de durabilidade: encontra os objetos que o catálogo diz {@code STORED} mas que não têm
 * as cópias que a configuração pede.
 *
 * <p>É a parte de "scrubbing" da fase 3 que dá para fazer com o que existe. Ela <b>não</b> lê byte
 * por byte comparando hash — isso exige que a réplica sirva o corpo para o Control Plane, e é o
 * próximo passo (o contrato de leitura por digest já existe, {@code GET /objects/by-digest}). O que
 * ela faz é o que a colocação permite afirmar:</p>
 *
 * <ul>
 * <li>conta só cópias {@code OK}: designada e não confirmada não é cópia, e é justamente o caso que
 * mais engana um painel que conte linhas;</li>
 * <li>devolve a <b>lacuna</b> — quantas faltam e em quais objetos —, não um booleano de saúde. Um
 * "está tudo ok" genérico esconderia o objeto que só tem uma cópia;</li>
 * <li>não apaga nem corrige nada. Reparar exige escolher nó e reenviar o corpo, e isso é decisão
 * (e custo) de quem opera, não efeito colateral de uma varredura.</li>
 * </ul>
 */
@Service
public class StorageScrubber {

	private final StorageCatalogRepository catalog;
	private final StoragePlacementService placement;
	private final AuditTrail audit;
	private final Clock clock;

	StorageScrubber(StorageCatalogRepository catalog, StoragePlacementService placement, AuditTrail audit,
			Clock clock) {
		this.catalog = catalog;
		this.placement = placement;
		this.audit = audit;
		this.clock = clock;
	}

	/** O que a varredura encontrou, incluindo os objetos que precisam de cópia nova. */
	public record Report(int replicasExpected, int objectsScanned, List<UnderReplicated> underReplicated) {

		/** Objeto com menos cópias confirmadas que o pedido, e quantas faltam. */
		public record UnderReplicated(String objectId, int confirmed, int missing, List<String> nodesWithCopy) {
		}
	}

	/**
	 * Percorre os objetos de um bucket (ou de todos, com {@code bucket} nulo) em páginas.
	 *
	 * @param maxObjects teto de objetos por rodada: a varredura é periódica e não pode monopolizar o
	 * catálogo nem a consulta de cada objeto num bucket grande.
	 */
	@Transactional(readOnly = true)
	public Report scan(String bucket, int maxObjects) {
		int expected = placement.targetReplicas();
		List<Report.UnderReplicated> gaps = new ArrayList<>();
		int scanned = 0;
		String after = null;
		while (scanned < Math.max(1, maxObjects)) {
			List<StorageObject> page = bucket == null
				? catalog.listAll(after, Math.min(200, Math.max(1, maxObjects) - scanned))
				: catalog.list(bucket, "", after, Math.min(200, Math.max(1, maxObjects) - scanned));
			if (page.isEmpty()) {
				break;
			}
			for (StorageObject object : page) {
				scanned++;
				String id = object.id();
				after = object.key();
				// Só objeto servível tem durabilidade a medir: PENDING ainda não tem corpo, DELETED
				// foi removido de propósito.
				if (!object.servable()) {
					continue;
				}
				List<StoragePlacement> confirmed = catalog.placements(id)
					.stream()
					.filter(p -> p.state() == StoragePlacement.State.OK)
					.toList();
				if (confirmed.size() < expected) {
					gaps.add(new Report.UnderReplicated(id, confirmed.size(), expected - confirmed.size(),
						confirmed.stream().map(StoragePlacement::nodeId).toList()));
				}
			}
			if (page.size() < 200) {
				break;
			}
		}
		if (!gaps.isEmpty()) {
			Instant now = clock.instant();
			audit.record("system", "STORAGE_SCRUB_UNDER_REPLICATED", bucket == null ? "*" : bucket,
				"{\"objects\":" + gaps.size() + ",\"expected\":" + expected + ",\"at\":\"" + now + "\"}");
		}
		return new Report(expected, scanned, List.copyOf(gaps));
	}

}