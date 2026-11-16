package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
 * O Storage no painel: capacidade declarada, disponível medido e uso medido — sem estimar.
 *
 * <p>O brief é explícito (§8.3): "os indicadores devem refletir dados efetivamente coletados,
 * nunca valores estimados apresentados como reais". O que estes testes fixam:</p>
 * <ul>
 * <li><b>disponível é o medido, não o declarado</b> — trocar um pelo outro passaria e mostraria
 * espaço que ninguém confirmou;</li>
 * <li><b>nó sem medida não vira zero</b> e nenhum nó medindo dá {@code null}, não {@code 0}: "sem
 * espaço" e "não sabemos" pedem decisões diferentes do operador;</li>
 * <li><b>nó que não é de armazenamento não soma disco</b> — CPU não dá permissão, e disco de
 * transcodificação não é espaço de objeto;</li>
 * <li><b>uso é só cópia OK</b>: designação é obrigação, não byte em disco.</li>
 * </ul>
 */
class StorageOverviewTests {

	private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

	private NetworkNodeRepository nodes;
	private NetworkRegionService regions;
	private StorageScrubber scrubber;
	private StorageCatalogRepository catalog;
	private StorageOverviewNodes overviewNodes;

	@BeforeEach
	void setUp() {
		nodes = mock(NetworkNodeRepository.class);
		regions = mock(NetworkRegionService.class);
		when(regions.outOfService()).thenReturn(List.of());
		overviewNodes = new StorageOverviewNodes(nodes, regions, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static NetworkNode node(String id, NodeStatus status, Set<NodeProfile> profiles, int declaredMb,
			Long freeMb, Instant lastSeen) {
		return new NetworkNode(id, "no-" + id, "user-" + id, status, profiles, "sudeste", "BR", "1.0.0", lastSeen,
			4, 4096, declaredMb, 100, null, null, null, null, freeMb, 0, null, null);
	}

	private static NetworkNode storage(String id, int declaredMb, Long freeMb) {
		return node(id, NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE), declaredMb, freeMb, NOW);
	}

	/** Nó de armazenamento com o TAMANHO do volume medido, além do livre. */
	private static NetworkNode storageComTotal(String id, int declaredMb, Long freeMb, Long totalMb) {
		return new NetworkNode(id, "no-" + id, "user-" + id, NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE),
			"sudeste", "BR", "1.0.0", NOW, 4, 4096, declaredMb, 100, null, null, null, null, freeMb, totalMb, null,
			0, null, null);
	}

	@Test
	void disponivel_e_o_medido_no_heartbeat_nao_o_declarado() {
		when(nodes.list(anyInt())).thenReturn(List.of(storage("nd_a", 102400, 5000L), storage("nd_b", 204800, 3000L)));

		StorageOverviewNodes.Capacity c = overviewNodes.capacity();

		assertThat(c.declaredMb()).isEqualTo(307200);
		assertThat(c.freeMb()).isEqualTo(8000L);
		assertThat(c.declaringNodes()).isEqualTo(2);
		assertThat(c.unmeasuredNodes()).isZero();
	}

	@Test
	void nenhum_no_medindo_da_nulo_e_nao_zero() {
		when(nodes.list(anyInt())).thenReturn(List.of(storage("nd_a", 102400, null)));

		StorageOverviewNodes.Capacity c = overviewNodes.capacity();

		// Zero diria "sem espaço"; o certo é "não sabemos", e é isso que faz o painel pedir heartbeat.
		assertThat(c.freeMb()).isNull();
		assertThat(c.unmeasuredNodes()).isEqualTo(1);
		assertThat(c.declaredMb()).isEqualTo(102400);
	}

	@Test
	void um_no_sem_medida_nao_soma_zero_e_aparece_na_contagem() {
		when(nodes.list(anyInt())).thenReturn(List.of(storage("nd_a", 102400, 5000L), storage("nd_b", 102400, null)));

		StorageOverviewNodes.Capacity c = overviewNodes.capacity();

		assertThat(c.freeMb()).isEqualTo(5000L);
		assertThat(c.unmeasuredNodes()).isEqualTo(1);
	}

	@Test
	void no_de_processamento_nao_soma_disco_de_armazenamento() {
		NetworkNode worker = node("nd_worker", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_WORKER), 512000, 400000L, NOW);
		when(nodes.list(anyInt())).thenReturn(List.of(storage("nd_a", 102400, 5000L), worker));

		StorageOverviewNodes.Capacity c = overviewNodes.capacity();

		assertThat(c.declaredMb()).isEqualTo(102400);
		assertThat(c.freeMb()).isEqualTo(5000L);
		assertThat(c.declaringNodes()).isEqualTo(1);
	}

	@Test
	void no_parado_nao_promete_disco() {
		NetworkNode velho = node("nd_velho", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE), 999999, 999999L,
			NOW.minus(Duration.ofHours(3)));
		when(nodes.list(anyInt())).thenReturn(List.of(storage("nd_a", 102400, 5000L), velho));

		StorageOverviewNodes.Capacity c = overviewNodes.capacity();

		assertThat(c.declaredMb()).isEqualTo(102400);
		assertThat(c.freeMb()).isEqualTo(5000L);
	}

	@Test
	void regiao_evacuada_nao_soma_capacidade() {
		// O evacuado está em OUTRA região: evacuar a dele não pode tirar a capacidade do são. Sem
		// isso o teste passaria por acidente -- os dois na mesma região saíam juntos.
		NetworkNode evacuado = new NetworkNode("nd_evac", "no-evac", "user-evac", NodeStatus.ACTIVE,
			Set.of(NodeProfile.NODE_STORAGE), "nordeste", "BR", "1.0.0", NOW, 4, 4096, 500000, 100, null, null, null,
			null, 500000L, 0, null, null);
		when(nodes.list(anyInt())).thenReturn(List.of(storage("nd_a", 102400, 5000L), evacuado));
		when(regions.outOfService())
			.thenReturn(List.of(com.socialcoisa.rede.network.domain.NetworkRegions.normalize("nordeste")));

		StorageOverviewNodes.Capacity c = overviewNodes.capacity();

		assertThat(c.declaredMb()).isEqualTo(102400);
		assertThat(c.declaringNodes()).isEqualTo(1);
	}

	@Test
	void no_da_infraestrutura_propria_soma_capacidade_porque_pode_guardar_objeto() {
		// NODE_LOCAL cobre todos os tipos, inclusive OBJECT_PUT: o nó da infraestrutura própria
		// guarda objeto no próprio disco. Filtrar por NOME de perfil o deixava fora e o painel
		// mostrava menos capacidade do que a rede tem.
		NetworkNode local = node("nd_local", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_LOCAL), 512000, 40000L,
			NOW);
		when(nodes.list(anyInt())).thenReturn(List.of(storage("nd_a", 102400, 5000L), local));

		StorageOverviewNodes.Capacity c = overviewNodes.capacity();

		assertThat(c.declaredMb()).isEqualTo(614400);
		assertThat(c.freeMb()).isEqualTo(45000L);
		assertThat(c.declaringNodes()).isEqualTo(2);
	}

	/**
	 * Brief §7: "2 TB de disco, 500 GB reservados pessoalmente, 1,5 TB oferecidos".
	 *
	 * <p>A reserva é {@code total - declarado}. Sem o total medido, ela não existe — e o teste com os
	 * DOIS nós distintos prova que a conta é de cada volume, não uma soma: 2 TB com 1,5 TB cedidos
	 * reserva 0,5 TB, e 500 GB com 500 GB cedidos reserva zero. Somar os totais daria 2,5 TB e
	 * somar declarados 2 TB, e o resultado seria o mesmo por acidente — é por isso que os lados são
	 * desiguais.</p>
	 */
	@Test
	void a_reserva_e_a_conta_de_cada_volume_total_menos_declarado() {
		when(nodes.list(anyInt())).thenReturn(List.of(
			storageComTotal("nd_grande", 1536000, 900000L, 2097152L),
			storageComTotal("nd_pequeno", 512000, 400000L, 512000L)));

		StorageOverviewNodes.Capacity c = overviewNodes.capacity();

		assertThat(c.disks()).extracting(StorageOverviewNodes.Disco::nodeId).containsExactly("nd_grande", "nd_pequeno");
		assertThat(c.disks().get(0).reservedMb()).isEqualTo(2097152L - 1536000L);
		assertThat(c.disks().get(1).reservedMb()).isZero();
		assertThat(c.unmeasuredDiskTotals()).isZero();
	}

	@Test
	void sem_total_medido_a_reserva_e_desconhecida_e_nao_zero() {
		// Runtime anterior à V341: conhece o livre, não o tamanho. Zero diria "não reservou nada".
		when(nodes.list(anyInt())).thenReturn(List.of(storage("nd_a", 102400, 5000L)));

		StorageOverviewNodes.Capacity c = overviewNodes.capacity();

		assertThat(c.disks()).hasSize(1);
		assertThat(c.disks().get(0).totalMb()).isNull();
		assertThat(c.disks().get(0).reservedMb()).isNull();
		assertThat(c.unmeasuredDiskTotals()).isEqualTo(1);
		// O livre continua sendo conhecido: os dois "sem medida" não se confundem.
		assertThat(c.unmeasuredNodes()).isZero();
	}

	@Test
	void participante_que_cedeu_mais_que_o_volume_tem_reserva_zero_e_nao_negativa() {
		// Divergência entre prometido e medido é diagnóstico; reserva negativa esconderia o erro.
		when(nodes.list(anyInt())).thenReturn(List.of(storageComTotal("nd_a", 2048000, 50000L, 512000L)));

		StorageOverviewNodes.Capacity c = overviewNodes.capacity();

		assertThat(c.disks().get(0).reservedMb()).isZero();
		assertThat(c.disks().get(0).totalMb()).isEqualTo(512000L);
	}

	/** Nó de armazenamento com o uso do conteúdo medido, além do declarado. */
	private static NetworkNode storageComUso(String id, int declaredMb, Long usedMb) {
		return new NetworkNode(id, "no-" + id, "user-" + id, NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE),
			"sudeste", "BR", "1.0.0", NOW, 4, 4096, declaredMb, 100, null, null, null, null, null, null, usedMb, 0,
			null, null);
	}

	/**
	 * Brief §7, "capacidade utilizada pelo Énóis" e "limite máximo de utilização": o uso é medido no
	 * NÓ, e o percentual sai contra o que ele cedeu. Dois nós com lados desiguais — um em metade, um
	 * acima do teto — provam que o percentual é de cada um e que passar de 100 aparece em vez de ser
	 * cortado.
	 */
	@Test
	void uso_do_conteudo_e_percentual_por_no_e_acima_do_teto_aparece() {
		when(nodes.list(anyInt())).thenReturn(List.of(
			storageComUso("nd_metade", 100000, 50000L),
			storageComUso("nd_acima", 20000, 22000L)));

		StorageOverviewNodes.Capacity c = overviewNodes.capacity();

		assertThat(c.unmeasuredUsage()).isZero();
		assertThat(c.disks().get(0).usedMb()).isEqualTo(50000L);
		assertThat(c.disks().get(0).usedPercent()).isEqualTo(50.0);
		assertThat(c.disks().get(1).usedMb()).isEqualTo(22000L);
		// Acima do teto: 110%, não 100. Cortar esconderia justamente o nó que consome além do prometido.
		assertThat(c.disks().get(1).usedPercent()).isEqualTo(110.0);
	}

	@Test
	void uso_nao_medido_e_desconhecido_e_nao_zero() {
		// Zero é resposta legítima (nó sem conteúdo); sem medida é outra coisa.
		when(nodes.list(anyInt())).thenReturn(List.of(storage("nd_a", 102400, 5000L)));

		StorageOverviewNodes.Capacity c = overviewNodes.capacity();

		assertThat(c.disks().get(0).usedMb()).isNull();
		assertThat(c.disks().get(0).usedPercent()).isNull();
		assertThat(c.unmeasuredUsage()).isEqualTo(1);
	}

	@Test
	void teto_zero_nao_vira_divisao_por_zero() {
		// 0 no contrato significa "sem teto", não "teto de zero": não há percentual a calcular.
		when(nodes.list(anyInt())).thenReturn(List.of(storageComUso("nd_sem_teto", 0, 5000L)));

		StorageOverviewNodes.Capacity c = overviewNodes.capacity();

		assertThat(c.disks().get(0).usedMb()).isEqualTo(5000L);
		assertThat(c.disks().get(0).usedPercent()).isNull();
	}

	@Test
	void uso_conta_so_copia_confirmada_e_nao_designacao() {
		catalog = mock(StorageCatalogRepository.class);
		when(catalog.summarizeObjects()).thenReturn(Map.of(
			"STORED", new long[] { 2, 30 },
			"DELETED", new long[] { 1, 10 }));
		// 2 cópias OK com 30 bytes e 1 designação com 60 "bytes" — a designação não é disco ocupado.
		when(catalog.summarizePlacements()).thenReturn(Map.of(
			"OK", new long[] { 2, 30 },
			"PENDING_REMOTE", new long[] { 1, 60 }));

		scrubber = mock(StorageScrubber.class);
		when(scrubber.scan(isNull(), anyInt())).thenReturn(new StorageScrubber.Report(2, 3, List.of(), 0, List.of()));
		StoragePlacementService placement = mock(StoragePlacementService.class);
		when(placement.targetReplicas()).thenReturn(2);

		var service = new StorageOverviewService(catalog, placement, overviewNodes, scrubber,
			Clock.fixed(NOW, ZoneOffset.UTC));
		var o = service.overview();

		assertThat(o.usedBytes()).isEqualTo(30);
		assertThat(o.objectsByState()).containsEntry("STORED", 2L).containsEntry("DELETED", 1L);
		assertThat(o.copiesByState()).containsEntry("PENDING_REMOTE", 1L);
		assertThat(o.replicasExpected()).isEqualTo(2);
	}

	@Test
	void lacuna_de_copias_vem_da_mesma_varredura_do_operador() {
		catalog = mock(StorageCatalogRepository.class);
		when(catalog.summarizeObjects()).thenReturn(Map.of());
		when(catalog.summarizePlacements()).thenReturn(Map.of());
		scrubber = mock(StorageScrubber.class);
		when(scrubber.scan(isNull(), anyInt())).thenReturn(new StorageScrubber.Report(2, 3,
			List.of(new StorageScrubber.Report.UnderReplicated("sob_1", 1, 1, List.of("nd_a"))), 0, List.of()));
		StoragePlacementService placement = mock(StoragePlacementService.class);
		when(placement.targetReplicas()).thenReturn(2);

		var o = new StorageOverviewService(catalog, placement, overviewNodes, scrubber,
			Clock.fixed(NOW, ZoneOffset.UTC)).overview();

		assertThat(o.objectsUnderReplicated()).isEqualTo(1);
		assertThat(o.usedBytes()).isZero();
	}
}