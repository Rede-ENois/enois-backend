package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StoragePlacement;

/**
 * O relatório que decide <b>se</b> vale rebalancear.
 *
 * <p>O que estes testes fixam — e o erro que cada um evita:</p>
 * <ul>
 * <li>só cópia {@code OK} conta: designada e não confirmada não ocupa disco; contá-la faria o
 * relatório mandar mover dados para aliviar um nó que não está cheio;</li>
 * <li>a régua é o <b>disco livre medido</b>, não o declarado: declarar 1 TB e ter 10 GB livres é
 * justamente o nó que NÃO deve receber mais;</li>
 * <li><b>sem medida não há régua</b>: com nenhum nó reportando disco, o relatório diz
 * {@code measurable=false} — "equilibrado" seria afirmar o que não foi medido;</li>
 * <li>nó sem medida fica no relatório com as cópias que tem, com participação de capacidade nula
 * (não zero);</li>
 * <li>o desvio só aponta quem tem <b>mais</b> que a folga justifica; a diferença é em pontos
 * percentuais e tem limiar, para um arquivo grande não virar alarme.</li>
 * </ul>
 */
class StorageBalanceReportTests {

	private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

	private NetworkNodeRepository nodes;
	private StorageCatalogRepository catalog;
	private StorageBalanceReport report;

	@BeforeEach
	void setUp() {
		nodes = mock(NetworkNodeRepository.class);
		catalog = mock(StorageCatalogRepository.class);
		report = new StorageBalanceReport(catalog, nodes, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void desequilibrioApontaQuemTemMaisCopiasQueAFolgaJustifica() {
		// nd_cheio tem 90% das cópias e só 25% da folga: desviado. nd_novo tem 10% e 75%.
		ready(storageNode("nd_cheio", 25600L), storageNode("nd_novo", 76800L));
		copies("nd_cheio", 9);
		copies("nd_novo", 1);

		var result = report.report();

		assertThat(result.measurable()).isTrue();
		assertThat(result.confirmedCopies()).isEqualTo(10);
		assertThat(result.skewed()).containsExactly("nd_cheio");
		var cheio = result.nodes().get(0);
		assertThat(cheio.nodeId()).isEqualTo("nd_cheio");
		assertThat(cheio.copiesSharePercent()).isEqualTo(90.0);
		assertThat(cheio.capacitySharePercent()).isEqualTo(25.0);
		assertThat(cheio.skewPp()).isEqualTo(65.0);
	}

	@Test
	void distribuicaoProporcionalNaoApontaNinguem() {
		ready(storageNode("nd_a", 50000L), storageNode("nd_b", 50000L));
		copies("nd_a", 4);
		copies("nd_b", 4);

		var result = report.report();

		assertThat(result.measurable()).isTrue();
		assertThat(result.skewed()).isEmpty();
	}

	@Test
	void copiaNaoConfirmadaNaoContaNoRelatorio() {
		ready(storageNode("nd_a", 50000L), storageNode("nd_b", 50000L));
		when(catalog.placementsOnNode("nd_a")).thenReturn(List.of(ok("nd_a"), ok("nd_a"),
			stale("nd_a"), stale("nd_a"), stale("nd_a")));
		copies("nd_b", 2);

		var result = report.report();

		// 2 cópias OK em nd_a, 2 em nd_b — equilibrado. Contar as STALE diria 5 contra 2.
		assertThat(result.confirmedCopies()).isEqualTo(4);
		assertThat(result.skewed()).isEmpty();
	}

	@Test
	void semDiscoMedidoNaoAfirmaEquilibrioNemDesequilibrio() {
		ready(nodeWithoutDisk("nd_a"), nodeWithoutDisk("nd_b"));
		copies("nd_a", 9);
		copies("nd_b", 1);

		var result = report.report();

		// Há cópias, mas não há régua: sem folga medida não se pode dizer que 9/1 está errado.
		assertThat(result.measurable()).isFalse();
		assertThat(result.skewed()).isEmpty();
		assertThat(result.nodes()).extracting("nodeId").containsExactly("nd_a", "nd_b");
		assertThat(result.nodes()).allSatisfy(share ->
			assertThat(share.capacitySharePercent()).isNull());
	}

	@Test
	void noSemMedidaDeDiscoFicaNoRelatorioComCapacidadeNula() {
		ready(storageNode("nd_medido", 100000L), nodeWithoutDisk("nd_sem_medida"));
		copies("nd_medido", 3);
		copies("nd_sem_medida", 3);

		var result = report.report();

		assertThat(result.measurable()).isTrue();
		var semMedida = result.nodes().stream()
			.filter(share -> share.nodeId().equals("nd_sem_medida"))
			.findFirst()
			.orElseThrow();
		// Nulo, não 0: zero de capacidade diria "sem folga", o oposto do que se sabe.
		assertThat(semMedida.diskFreeMb()).isNull();
		assertThat(semMedida.capacitySharePercent()).isNull();
		assertThat(semMedida.skewPp()).isNull();
		// E as cópias dele continuam contadas: omitir esconderia o que ele guarda.
		assertThat(semMedida.confirmedCopies()).isEqualTo(3);
		assertThat(result.confirmedCopies()).isEqualTo(6);
	}

	@Test
	void semNoProntoORevatorioNaoInventaBase() {
		when(nodes.findCandidates(any(), anyInt())).thenReturn(List.of());

		var result = report.report();

		assertThat(result.nodesConsidered()).isZero();
		assertThat(result.measurable()).isFalse();
		assertThat(result.nodes()).isEmpty();
	}

	// ---------------------------------------------------------------- apoio

	private void ready(NetworkNode... found) {
		when(nodes.findCandidates(any(), anyInt())).thenReturn(List.of(found));
	}

	private void copies(String nodeId, int count) {
		List<StoragePlacement> placements = new java.util.ArrayList<>();
		for (int i = 0; i < count; i++) {
			placements.add(ok(nodeId));
		}
		when(catalog.placementsOnNode(nodeId)).thenReturn(placements);
	}

	private static StoragePlacement ok(String nodeId) {
		return new StoragePlacement("sob_x", 1, nodeId, 10, "abc", StoragePlacement.State.OK, NOW);
	}

	private static StoragePlacement stale(String nodeId) {
		return new StoragePlacement("sob_x", 1, nodeId, 10, "abc", StoragePlacement.State.STALE, NOW);
	}

	private static NetworkNode storageNode(String id, long diskFreeMb) {
		return new NetworkNode(id, id, "owner", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE), "sudeste", "BR",
			"0.1.0", NOW, 4, 8192, 102400, 100, null, null, null, null, diskFreeMb, 0, null, null);
	}

	private static NetworkNode nodeWithoutDisk(String id) {
		return new NetworkNode(id, id, "owner", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE), "sudeste", "BR",
			"0.1.0", NOW, 4, 8192, 102400, 100, null, null, null, null, null, 0, null, null);
	}
}