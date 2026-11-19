package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;

/**
 * O Storage no painel de operação (§8.2 do brief do Global Cooperative): capacidade, uso,
 * objetos e o estado das cópias.
 *
 * <p><b>Nada aqui é estimado.</b> O brief é explícito: "os indicadores devem refletir dados
 * efetivamente coletados, nunca valores estimados apresentados como reais". Por isso cada número
 * vem de uma consulta de agregação sobre o catálogo, e cada um diz o que é:</p>
 *
 * <ul>
 * <li><b>capacidade</b> é o que os nós <b>declararam</b> ceder ({@code declaredDiskMb}), somado;</li>
 * <li><b>reservado</b> é a conta de cada volume que ficou fora do Énóis por decisão do participante
 * ({@code total - declarado}, brief §7), uma linha em {@code disks} por nó. Não é somado: reserva
 * é conta de um disco, e a soma entre nós não responde pergunta nenhuma. {@code null} por nó
 * quando ele não reportou o tamanho (runtime anterior à V341), e {@code unmeasuredDiskTotals} diz
 * quantos ficaram de fora;</li>
 * <li><b>disponível</b> é o disco livre <b>medido</b> no heartbeat ({@code observedDiskFreeMb}). Nó
 * sem medida entra em {@code unmeasuredNodes} e <b>não</b> soma zero — não medir não é estar sem
 * espaço;</li>
 * <li><b>utilizado</b> é o bytes das cópias {@code OK}, que o Control Plane mediu ao receber o corpo.
 * Designação ({@code PENDING_REMOTE}) não ocupa disco e não entra;</li>
 * <li><b>faltando cópia</b> é a contagem de objetos com menos cópias que o alvo, ao lado do alvo —
 * sem o alvo, "3 abaixo" não diz se é saudável.</li>
 * </ul>
 *
 * <p>Este serviço <b>não</b> lê corpo nem repara nada: é leitura de catálogo. A varredura que relê
 * bytes é outro caminho ({@code scrub?readBodies}), porque custa disco de quem contribui.</p>
 */
@Service
public class StorageOverviewService {

	/**
	 * O resumo. {@code objects} e {@code copies} são mapas por estado, não totais achatados: o
	 * painel precisa distinguir objeto {@code STORED} de {@code DELETED} (lápide ainda ocupa chave,
	 * não ocupa disco) e cópia {@code OK} de {@code STALE}.
	 */
	public record Overview(long capacityMb, Long freeMb, int nodesDeclaringCapacity, int unmeasuredNodes,
			int unmeasuredDiskTotals, int unmeasuredUsage, List<StorageOverviewNodes.Disco> disks,
			long usedBytes, Map<String, Long> objectsByState, Map<String, Long> copiesByState,
			int objectsUnderReplicated, int replicasExpected, Map<String, Long> objectsByDurability, int onlineNodes,
			int offlineNodes, List<StorageOverviewNodes.OfflineNode> offline, int buckets,
			Map<String, BucketUsage> usageByBucket) {
	}

	/** O que um bucket tem: objetos servíveis e bytes medidos. */
	public record BucketUsage(long objects, long bytes) {
	}

	private final StorageCatalogRepository catalog;
	private final StoragePlacementService placement;
	private final StorageOverviewNodes nodes;
	private final StorageScrubber scrubber;
	private final Clock clock;

	StorageOverviewService(StorageCatalogRepository catalog, StoragePlacementService placement,
			StorageOverviewNodes nodes, StorageScrubber scrubber, Clock clock) {
		this.catalog = catalog;
		this.placement = placement;
		this.nodes = nodes;
		this.scrubber = scrubber;
		this.clock = clock;
	}

	/** O disco de um nó: a mesma conta do painel geral, para a página do nó. */
	@Transactional(readOnly = true)
	public StorageOverviewNodes.Disco diskOf(String nodeId) {
		return nodes.diskOf(nodeId);
	}

	@Transactional(readOnly = true)
	public Overview overview() {
		Map<String, long[]> objetos = catalog.summarizeObjects();
		// Por bucket: o numero de buckets e o tamanho deste mapa. Bucket nao e uma tabela -- existe
		// enquanto tiver objeto --, entao perguntar "quantos buckets" e contar as chaves distintas.
		Map<String, BucketUsage> porBucket = new LinkedHashMap<>();
		catalog.summarizeBuckets().forEach((nome, par) -> porBucket.put(nome, new BucketUsage(par[0], par[1])));
		Map<String, long[]> copias = catalog.summarizePlacements();

		Map<String, Long> objetosPorEstado = new LinkedHashMap<>();
		objetos.forEach((estado, par) -> objetosPorEstado.put(estado, par[0]));
		Map<String, Long> copiasPorEstado = new LinkedHashMap<>();
		copias.forEach((estado, par) -> copiasPorEstado.put(estado, par[0]));

		// Uso é só a cópia medida: designação é obrigação, não byte em disco.
		long usado = copias.getOrDefault("OK", new long[] { 0, 0 })[1];

		var capacidade = nodes.capacity();
		// A lacuna usa a mesma varredura do operador: dois números diferentes para "faltam cópias"
		// seriam dois lugares para discordar.
		var lacuna = scrubber.scan(null, 1000);

		return new Overview(capacidade.declaredMb(), capacidade.freeMb(), capacidade.declaringNodes(),
			capacidade.unmeasuredNodes(), capacidade.unmeasuredDiskTotals(), capacidade.unmeasuredUsage(),
			capacidade.disks(), usado,
			objetosPorEstado, copiasPorEstado, lacuna.underReplicated().size(), placement.targetReplicas(),
			lacuna.byDurability(), capacidade.onlineNodes(), capacidade.offlineNodes(), capacidade.offline(),
			porBucket.size(), porBucket);
	}
}