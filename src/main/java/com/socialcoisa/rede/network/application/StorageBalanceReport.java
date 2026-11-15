package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.StoragePlacement;

/**
 * Equilíbrio da distribuição: os nós de armazenamento estão recebendo proporcionalmente à folga
 * deles?
 *
 * <p>O brief §16 pede rebalanceamento quando um nó novo entra. Antes de mover byte nenhum é preciso
 * responder <b>se</b> está desequilibrado e <b>onde</b> — mover cópias é transferência de dados pela
 * rede de quem participa, e fazê-lo por palpite custa a banda de todo mundo. Este serviço entrega
 * essa medição; ele <b>não move nada</b>.</p>
 *
 * <p>O que é medido, e com o que:</p>
 *
 * <ul>
 * <li><b>cópias confirmadas por nó</b>, contando só {@code OK}: designada e não confirmada não
 * ocupa disco, e contá-la faria o relatório dizer que um nó está cheio de cópias que não existem;</li>
 * <li><b>participação</b> de cada nó nas cópias totais, comparada com a participação dele na
 * <b>capacidade pronta</b> (disco livre medido, só de nó ACTIVE, com heartbeat fresco e perfil de
 * armazenamento). É a régua que a colocação já usa: quem tem mais folga deveria ter mais cópias;</li>
 * <li><b>desvio</b> = participação nas cópias menos participação na capacidade. Positivo = o nó tem
 * mais do que a folga dele justifica.</li>
 * </ul>
 *
 * <p><b>O que não afirma:</b> não há host físico, operadora nem rack no cadastro, então "desequilíbrio
 * entre domínios de falha" só é medível por região — e isso já está na colocação. O relatório não
 * escolhe objeto para migrar: a decisão de mover depende de banda, janela e prioridade, que são
 * escolha de quem opera.</p>
 */
@Service
public class StorageBalanceReport {

	/**
	 * Desvio (em pontos percentuais) acima do qual o nó é apontado como fora do equilíbrio. Abaixo
	 * disto a diferença é ruído: com poucos objetos, um único arquivo grande move vários pontos.
	 */
	static final double SKEW_THRESHOLD_PP = 15.0;

	private final StorageCatalogRepository catalog;
	private final NetworkNodeRepository nodes;
	private final Clock clock;

	StorageBalanceReport(StorageCatalogRepository catalog, NetworkNodeRepository nodes, Clock clock) {
		this.catalog = catalog;
		this.nodes = nodes;
		this.clock = clock;
	}

	/** Um nó no relatório: o que tem, o que a folga dele sugere, e a diferença. */
	public record NodeShare(String nodeId, int confirmedCopies, double copiesSharePercent, Long diskFreeMb,
			Double capacitySharePercent, Double skewPp) {
	}

	/**
	 * O relatório. {@code skewed} é vazio quando não há base para afirmar desequilíbrio — e é aqui
	 * que o caso fácil de errar aparece: <b>sem capacidade medida</b> (nenhum nó reportou disco) não
	 * existe régua, e dizer "equilibrado" seria afirmar o que não foi medido. Os campos de
	 * participação ficam nulos e o relatório declara {@code measurable=false}.
	 */
	public record Report(int nodesConsidered, int confirmedCopies, boolean measurable, List<NodeShare> nodes,
			List<String> skewed) {
	}

	@Transactional(readOnly = true)
	public Report report() {
		var now = clock.instant();
		var ready = nodes.findCandidates(now.minusSeconds(90), 100);
		if (ready.isEmpty()) {
			return new Report(0, 0, false, List.of(), List.of());
		}

		// Cópias confirmadas por nó. `placementsOnNode` é uma consulta por nó: a lista de nós é
		// limitada (o teto dos candidatos) e o relatório é de operação, não de rota quente.
		Map<String, Integer> copies = new LinkedHashMap<>();
		long totalCopies = 0;
		for (NetworkNode node : ready) {
			int count = (int) catalog.placementsOnNode(node.id())
				.stream()
				.filter(p -> p.state() == StoragePlacement.State.OK)
				.count();
			copies.put(node.id(), count);
			totalCopies += count;
		}

		// Capacidade: só o disco MEDIDO. Nó sem medida não entra na régua (não sabemos a folga), mas
		// continua no relatório com as cópias que tem -- omiti-lo esconderia as cópias dele.
		Map<String, Long> free = new LinkedHashMap<>();
		long totalFree = 0;
		for (NetworkNode node : ready) {
			Long diskFree = node.observedDiskFreeMb();
			if (diskFree != null) {
				free.put(node.id(), diskFree);
				totalFree += diskFree;
			}
		}

		boolean measurable = totalFree > 0 && totalCopies > 0;
		List<NodeShare> shares = new ArrayList<>();
		for (NetworkNode node : ready) {
			double copiesPercent = totalCopies == 0 ? 0 : (copies.get(node.id()) * 100.0) / totalCopies;
			Long diskFree = free.get(node.id());
			double capacityPercent = diskFree == null || totalFree == 0 ? Double.NaN : (diskFree * 100.0) / totalFree;
			// Desvio só tem sentido com as duas medidas: cópia de um lado, folga do outro.
			double skew = Double.isNaN(capacityPercent) ? Double.NaN : copiesPercent - capacityPercent;
			// Nulo, não zero: `-1` diria "sem folga" (o oposto de não medido) e ordenaria o nó no
			// fim do relatório como se ele estivesse cheio.
			shares.add(new NodeShare(node.id(), copies.get(node.id()), round(copiesPercent), diskFree,
				Double.isNaN(capacityPercent) ? null : round(capacityPercent),
				Double.isNaN(skew) ? null : round(skew)));
		}
		// Sem desvio medido vai para o fim (não para o topo): o relatório é lido de cima, e o nó sem
		// folga reportada não é o mais desequilibrado — é o que não tem como ser avaliado.
		shares.sort(Comparator.comparing((NodeShare share) -> share.skewPp() == null,
			Comparator.naturalOrder()).thenComparing(share -> share.skewPp() == null ? 0.0 : -share.skewPp())
			.thenComparing(NodeShare::nodeId));

		List<String> skewed = measurable
			? shares.stream()
				.filter(share -> share.skewPp() != null && share.skewPp() > SKEW_THRESHOLD_PP)
				.map(NodeShare::nodeId)
				.toList()
			: List.of();
		return new Report(ready.size(), (int) totalCopies, measurable, List.copyOf(shares), skewed);
	}

	private static double round(double value) {
		return Math.round(value * 10) / 10.0;
	}
}