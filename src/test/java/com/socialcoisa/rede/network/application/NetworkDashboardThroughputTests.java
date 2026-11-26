package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkTaskRepository;
import com.socialcoisa.rede.network.application.port.out.NodeObservabilityRepository;
import com.socialcoisa.rede.network.application.port.out.NodeObservabilityRepository.Sample;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.TaskStatus;

/**
 * O throughput global da rede — §8.3 do brief ("throughput global") sobre a medida do §8.2
 * ("throughput de entrada e saída").
 *
 * <p>O que estes testes fixam, e por quê:</p>
 * <ul>
 * <li><b>é a soma das TAXAS, não dos acumulados</b>: o acumulado desde o boot é outra medida (o
 * painel do participante), e somar acumulados não responde "quanto está passando agora";</li>
 * <li><b>cada campo soma só sobre quem o mediu</b>: um nó sem a saída medida não afunda a soma da
 * saída com um zero que ninguém leu;</li>
 * <li><b>nó que não está vivo ou não tem ponto do minuto corrente não entra</b>: a taxa de uma hora
 * atrás somaria tráfego que já é passado;</li>
 * <li><b>sem nenhuma medida, o valor é {@code null}, não 0</b>: zero afirmaria "nada passou" para o
 * que ninguém mediu.</li>
 * </ul>
 */
class NetworkDashboardThroughputTests {

	private static final Instant NOW = Instant.parse("2026-11-26T20:15:30Z");
	private static final Instant MINUTE = NOW.truncatedTo(ChronoUnit.MINUTES);

	private List<NetworkNode> nos;
	private NodeObservabilityRepository samples;
	private NetworkDashboardService service;

	@BeforeEach
	void setUp() {
		nos = new java.util.ArrayList<>();
		NetworkNodeRepository nodes = mock(NetworkNodeRepository.class);
		when(nodes.list(anyInt())).thenAnswer(call -> List.copyOf(nos));
		NetworkTaskRepository tasks = mock(NetworkTaskRepository.class);
		when(tasks.countDoneByNode(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(java.util.Map.of());
		when(tasks.countFailedByNode(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(java.util.Map.of());
		when(tasks.countByStatus(TaskStatus.DONE)).thenReturn(0L);
		when(tasks.countByStatus(TaskStatus.FAILED)).thenReturn(0L);
		NetworkRegionService regions = mock(NetworkRegionService.class);
		when(regions.outOfService()).thenReturn(List.of());
		samples = mock(NodeObservabilityRepository.class);
		when(samples.samples(anyString(), anyInt())).thenReturn(List.of());
		com.socialcoisa.rede.network.application.port.out.ContentVersionRepository versions =
			mock(com.socialcoisa.rede.network.application.port.out.ContentVersionRepository.class);
		when(versions.countBehind(anyString(), org.mockito.ArgumentMatchers.any(
			com.socialcoisa.rede.network.domain.ContentKind.class))).thenReturn(0L);
		service = new NetworkDashboardService(nodes, regions, tasks, samples, versions,
			Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static NetworkNode node(String id, NodeStatus status, Instant lastSeen, Long bytesInMb) {
		return new NetworkNode(id, "no-" + id, "user-" + id, status, Set.of(NodeProfile.NODE_WORKER), "sudeste",
			"BR", "1.0.0", lastSeen, 4, 4096, 102400, 100, null, null, null, null, null, null, null, null, null,
			null, bytesInMb, null, 0, null, null, null, null);
	}

	private void sampleDe(String nodeId, Instant at, Double in, Double out) {
		when(samples.samples(eq(nodeId), eq(1)))
			.thenReturn(List.of(new Sample(nodeId, at, null, null, null, null, null, "ACTIVE", null, null, null,
				null, null, null, null, in, out)));
	}

	@Test
	void a_soma_e_das_taxas_medidas_e_o_acumulado_vai_em_campo_proprio() {
		nos.add(node("nd_a", NodeStatus.ACTIVE, NOW, 9_000L));
		nos.add(node("nd_b", NodeStatus.ACTIVE, NOW, 1_000L));
		sampleDe("nd_a", MINUTE, 1.5, 0.25);
		sampleDe("nd_b", MINUTE, 2.0, 0.75);

		NetworkDashboardService.Throughput t = service.dashboard().throughput();

		assertThat(t.inMbps()).isEqualTo(3.5);
		assertThat(t.outMbps()).isEqualTo(1.0);
		assertThat(t.measuredNodes()).isEqualTo(2);
		// O acumulado é do painel do participante (§18) e continua somado em MiB — mas em campo
		// próprio: 10 GiB desde o boot não pode aparecer como "10 MiB/s".
		assertThat(t.measuredBytesSinceBoot()).isEqualTo(10_000L);
	}

	@Test
	void cada_campo_soma_so_sobre_quem_o_mediu() {
		nos.add(node("nd_a", NodeStatus.ACTIVE, NOW, null));
		nos.add(node("nd_b", NodeStatus.ACTIVE, NOW, null));
		sampleDe("nd_a", MINUTE, 4.0, null);
		// O ponto de nd_b não mediu nenhuma das duas (o primeiro ciclo dele ainda não tem intervalo).
		sampleDe("nd_b", MINUTE, null, null);

		NetworkDashboardService.Throughput t = service.dashboard().throughput();

		// A saída não foi medida por ninguém: nula, e não 0. A entrada é a de quem mediu.
		assertThat(t.inMbps()).isEqualTo(4.0);
		assertThat(t.outMbps()).isNull();
		assertThat(t.measuredNodes()).isEqualTo(1);
		assertThat(t.measuredBytesSinceBoot()).isNull();
	}

	@Test
	void no_fora_ou_sem_ponto_do_minuto_corrente_nao_entra_na_soma() {
		nos.add(node("nd_vivo", NodeStatus.ACTIVE, NOW, null));
		nos.add(node("nd_calado", NodeStatus.ACTIVE, NOW.minusSeconds(3600), null));
		nos.add(node("nd_suspenso", NodeStatus.SUSPENDED, NOW, null));
		sampleDe("nd_vivo", MINUTE, 1.0, 1.0);
		sampleDe("nd_calado", MINUTE, 50.0, 50.0);
		sampleDe("nd_suspenso", MINUTE, 70.0, 70.0);
		// Um nó vivo cujo último ponto é do minuto anterior: a taxa dele é de antes, não de agora.
		nos.add(node("nd_atrasado", NodeStatus.ACTIVE, NOW, null));
		sampleDe("nd_atrasado", MINUTE.minusSeconds(60), 30.0, 30.0);

		NetworkDashboardService.Throughput t = service.dashboard().throughput();

		assertThat(t.inMbps()).isEqualTo(1.0);
		assertThat(t.outMbps()).isEqualTo(1.0);
		assertThat(t.measuredNodes()).isEqualTo(1);
	}

	@Test
	void sem_medida_alguma_o_throughput_e_nulo_e_nao_zero() {
		nos.add(node("nd_a", NodeStatus.ACTIVE, NOW, null));

		NetworkDashboardService.Throughput t = service.dashboard().throughput();

		assertThat(t.inMbps()).isNull();
		assertThat(t.outMbps()).isNull();
		assertThat(t.measuredNodes()).isZero();
		assertThat(t.measuredBytesSinceBoot()).isNull();
	}
}