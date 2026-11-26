package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkTaskRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.TaskStatus;

/**
 * A distribuição de trabalho entre os nós — §8.4 do brief, "identificar se a infraestrutura está
 * realmente distribuindo o processamento ou se o Master continua absorvendo trabalho".
 *
 * <p>O que estes testes fixam, e por quê:</p>
 * <ul>
 * <li><b>nó que nunca executou aparece com zero</b>, não some da lista: a ausência é o dado que
 * mostra o nó ocioso — se ele fosse omitido, "nó sem tarefa" e "nó inexistente" ficariam iguais;</li>
 * <li><b>sem conclusão alguma, a fatia é {@code null}</b> — 0% e 100% seriam lidos como "distribui
 * igual" e "tudo num nó" para o mesmo caso vazio;</li>
 * <li><b>concentração só vale com duas réplicas ativas</b>: com uma só, 100% é a única opção, não
 * falta de distribuição;</li>
 * <li><b>concluídas sem nó não desaparecem</b>: entram em {@code unassigned} e no total;</li>
 * <li><b>falha definitiva é contada por nó</b>: é o outro lado da cooperação, e um nó que só falha
 * não pode parecer ocioso.</li>
 * </ul>
 */
class NetworkDashboardDistributionTests {

	private static final Instant NOW = Instant.parse("2026-11-15T20:00:00Z");

	private List<NetworkNode> nos;
	private NetworkTaskRepository tasks;
	private NetworkDashboardService service;
	private final Map<String, Long> done = new java.util.HashMap<>();
	private final Map<String, Long> failed = new java.util.HashMap<>();
	private long totalDone;
	private long totalFailed;

	@BeforeEach
	void setUp() {
		nos = new java.util.ArrayList<>();
		done.clear();
		failed.clear();
		totalDone = 0;
		totalFailed = 0;

		NetworkNodeRepository nodes = mock(NetworkNodeRepository.class);
		when(nodes.list(org.mockito.ArgumentMatchers.anyInt()))
			.thenAnswer(call -> List.copyOf(nos));

		tasks = mock(NetworkTaskRepository.class);
		when(tasks.countDoneByNode(org.mockito.ArgumentMatchers.anyCollection())).thenAnswer(call -> {
			Collection<String> ids = call.getArgument(0);
			return done.entrySet().stream().filter(e -> ids.contains(e.getKey()))
				.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		});
		when(tasks.countFailedByNode(org.mockito.ArgumentMatchers.anyCollection())).thenAnswer(call -> {
			Collection<String> ids = call.getArgument(0);
			return failed.entrySet().stream().filter(e -> ids.contains(e.getKey()))
				.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		});
		// O total do catálogo é o de `countByStatus`. Sem um valor explícito ele é a soma por nó:
		// nenhum teste "sem dono" por acidente — o caso de órfã é `totalDone` maior, e o teste o diz.
		when(tasks.countByStatus(TaskStatus.DONE)).thenAnswer(
			call -> totalDone != 0 ? totalDone : done.values().stream().mapToLong(Long::longValue).sum());
		when(tasks.countByStatus(TaskStatus.FAILED)).thenReturn(0L);

		NetworkRegionService regions = mock(NetworkRegionService.class);
		when(regions.outOfService()).thenReturn(List.of());
		// Sem amostra gravada: o throughput global fica "não medido" nestes casos, que são da
		// distribuição de trabalho (a taxa tem teste próprio, NetworkDashboardThroughputTests).
		com.socialcoisa.rede.network.application.port.out.NodeObservabilityRepository samples =
			mock(com.socialcoisa.rede.network.application.port.out.NodeObservabilityRepository.class);
		when(samples.samples(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
			.thenReturn(List.of());
		service = new NetworkDashboardService(nodes, regions, tasks, samples, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static NetworkNode node(String id, NodeStatus status, Integer runningNow) {
		return new NetworkNode(id, "no-" + id, "user-" + id, status, Set.of(NodeProfile.NODE_WORKER), "sudeste", "BR",
			"1.0.0", NOW, 4, 4096, 102400, 100, null, null, runningNow, null, null, 0, null, null);
	}

	@Test
	void no_que_nunca_executou_aparece_com_zero_em_vez_de_sumir() {
		nos.add(node("nd_a", NodeStatus.ACTIVE, null));
		nos.add(node("nd_b", NodeStatus.ACTIVE, null));
		done.put("nd_a", 3L);

		NetworkDashboardService.Distribution load = service.dashboard().load();

		assertThat(load.nodes()).hasSize(2);
		assertThat(load.nodes()).extracting(NetworkDashboardService.NodeLoad::nodeId)
			.containsExactlyInAnyOrder("nd_a", "nd_b");
		assertThat(load.nodes()).filteredOn(n -> n.nodeId().equals("nd_b"))
			.singleElement()
			.extracting(NetworkDashboardService.NodeLoad::done)
			.isEqualTo(0L);
	}

	@Test
	void sem_conclusao_alguma_a_fatia_e_nula_e_nao_zero() {
		nos.add(node("nd_a", NodeStatus.ACTIVE, null));
		nos.add(node("nd_b", NodeStatus.ACTIVE, null));

		NetworkDashboardService.Distribution load = service.dashboard().load();

		assertThat(load.totalDone()).isZero();
		assertThat(load.concentratedShare()).isNull();
		assertThat(load.singleNode()).isFalse();
	}

	@Test
	void todo_o_trabalho_num_no_com_duas_replicas_ativas_e_concentracao() {
		nos.add(node("nd_a", NodeStatus.ACTIVE, null));
		nos.add(node("nd_b", NodeStatus.ACTIVE, null));
		done.put("nd_a", 5L);

		NetworkDashboardService.Distribution load = service.dashboard().load();

		assertThat(load.totalDone()).isEqualTo(5);
		assertThat(load.concentratedShare()).isEqualTo(100.0);
		assertThat(load.singleNode()).isTrue();
	}

	@Test
	void trabalho_dividido_nao_e_concentracao_e_a_fatia_e_a_do_maior() {
		nos.add(node("nd_a", NodeStatus.ACTIVE, null));
		nos.add(node("nd_b", NodeStatus.ACTIVE, null));
		done.put("nd_a", 3L);
		done.put("nd_b", 1L);

		NetworkDashboardService.Distribution load = service.dashboard().load();

		assertThat(load.totalDone()).isEqualTo(4);
		assertThat(load.concentratedShare()).isEqualTo(75.0);
		assertThat(load.singleNode()).isFalse();
	}

	@Test
	void com_uma_replica_so_cem_por_cento_nao_e_concentracao() {
		nos.add(node("nd_a", NodeStatus.ACTIVE, null));
		done.put("nd_a", 2L);

		NetworkDashboardService.Distribution load = service.dashboard().load();

		// Uma réplica só não tem para onde distribuir: dizer "concentrado" acusaria o operador de um
		// problema que é a topologia, não a distribuição.
		assertThat(load.concentratedShare()).isEqualTo(100.0);
		assertThat(load.singleNode()).isFalse();
	}

	@Test
	void concluida_sem_no_entra_no_total_e_em_unassigned() {
		nos.add(node("nd_a", NodeStatus.ACTIVE, null));
		done.put("nd_a", 2L);
		totalDone = 5L;

		NetworkDashboardService.Distribution load = service.dashboard().load();

		assertThat(load.totalDone()).isEqualTo(5);
		assertThat(load.unassigned()).isEqualTo(3);
	}

	@Test
	void falha_definitiva_aparece_por_no_e_nao_faz_o_no_parecer_ocioso() {
		nos.add(node("nd_a", NodeStatus.ACTIVE, null));
		nos.add(node("nd_b", NodeStatus.ACTIVE, null));
		failed.put("nd_b", 2L);

		NetworkDashboardService.Distribution load = service.dashboard().load();

		assertThat(load.totalFailed()).isEqualTo(2);
		assertThat(load.nodes()).filteredOn(n -> n.nodeId().equals("nd_b"))
			.singleElement()
			.extracting(NetworkDashboardService.NodeLoad::failed)
			.isEqualTo(2L);
	}

	@Test
	void tarefa_em_execucao_nao_reportada_fica_nula_e_nao_vira_zero() {
		nos.add(node("nd_a", NodeStatus.ACTIVE, null));
		nos.add(node("nd_b", NodeStatus.ACTIVE, 2));

		NetworkDashboardService.Distribution load = service.dashboard().load();

		assertThat(load.nodes()).filteredOn(n -> n.nodeId().equals("nd_a"))
			.singleElement()
			.extracting(NetworkDashboardService.NodeLoad::runningNow)
			.isNull();
		assertThat(load.nodes()).filteredOn(n -> n.nodeId().equals("nd_b"))
			.singleElement()
			.extracting(NetworkDashboardService.NodeLoad::runningNow)
			.isEqualTo(2);
	}

}
