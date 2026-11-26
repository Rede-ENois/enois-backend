package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.application.NetworkDashboardService.HealthAlert;
import com.socialcoisa.rede.network.application.NetworkDashboardService.HealthGroup;
import com.socialcoisa.rede.network.application.port.out.ContentVersionRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkTaskRepository;
import com.socialcoisa.rede.network.application.port.out.NodeObservabilityRepository;
import com.socialcoisa.rede.network.domain.ContentKind;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.TaskStatus;

/**
 * As quatro dimensões de saúde do §8.8 na rede inteira — o topo do painel.
 *
 * <p>Antes, a dimensão existia só na página do nó: para saber se a rede estava saudável o operador
 * abria nó por nó. O que estes testes fixam, e por quê:</p>
 * <ul>
 * <li><b>cada dimensão conta separado</b>: um nó silencioso e um nó suspenso caem em dimensões
 * diferentes, e um "verde" único é exatamente o que o brief proíbe;</li>
 * <li><b>pendente de aprovação fica fora do quadro</b>: mesma régua de {@code unavailable} no topo —
 * fila de aprovação não é incidente;</li>
 * <li><b>o atraso entra na aplicação</b>: nó em dia e nó atrás da origem se separam;</li>
 * <li><b>atraso não consultado não vira "em dia"</b>: acima do teto o quadro se declara incompleto;</li>
 * <li><b>a lista de alertas é determinística</b>: a mais grave primeiro, depois por nó — senão o
 * recorte exibido dependeria da ordem do banco;</li>
 * <li><b>um alerta por nó, o mais grave</b>: o suspenso que também está saturado não aparece duas
 * vezes.</li>
 * </ul>
 */
class NetworkDashboardHealthTests {

	private static final Instant NOW = Instant.parse("2026-11-26T20:00:00Z");

	private List<NetworkNode> nos;
	private ContentVersionRepository versions;
	private NetworkTaskRepository tasks;
	private NetworkDashboardService service;

	@BeforeEach
	void setUp() {
		nos = new java.util.ArrayList<>();
		NetworkNodeRepository nodes = mock(NetworkNodeRepository.class);
		when(nodes.list(anyInt())).thenAnswer(call -> List.copyOf(nos));
		tasks = mock(NetworkTaskRepository.class);
		when(tasks.countDoneByNode(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(Map.of());
		when(tasks.countFailedByNode(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(Map.of());
		when(tasks.countByStatus(TaskStatus.DONE)).thenReturn(0L);
		when(tasks.countByStatus(TaskStatus.FAILED)).thenReturn(0L);
		// Fila pendente zerada: estes casos medem a saúde dos nós. O alerta de "trabalho sem alvo" tem
		// teste próprio (NetworkModesTests, pela rota, e o de rede abaixo).
		when(tasks.countByStatus(TaskStatus.PENDING)).thenReturn(0L);
		when(tasks.countPendingByType(anyString())).thenReturn(0L);
		NetworkRegionService regions = mock(NetworkRegionService.class);
		when(regions.outOfService()).thenReturn(List.of());
		NodeObservabilityRepository samples = mock(NodeObservabilityRepository.class);
		when(samples.samples(anyString(), anyInt())).thenReturn(List.of());
		versions = mock(ContentVersionRepository.class);
		when(versions.countBehind(anyString(), org.mockito.ArgumentMatchers.any(ContentKind.class))).thenReturn(0L);
		service = new NetworkDashboardService(nodes, regions, tasks, samples, versions,
			Clock.fixed(NOW, ZoneOffset.UTC));
	}

	/** Nó saudável: ativo, viu o CP agora, um perfil, sem falhas e com uma vaga livre. */
	private static NetworkNode saudavel(String id) {
		return new NetworkNode(id, "no-" + id, "user-" + id, NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_WORKER),
			"sudeste", "BR", "1.0.0", NOW, 4, 4096, 102400, 100, 10.0, 512, null, 1, null, 51200L, 102400L, null,
			null, null, null, null, 0, null, null, BigDecimal.valueOf(45), null);
	}

	private static NetworkNode com(String id, NodeStatus status, Set<NodeProfile> perfis, Instant visto,
			Integer rodando, int falhas, Double cpu) {
		return new NetworkNode(id, "no-" + id, "user-" + id, status, perfis, "sudeste", "BR", "1.0.0", visto, 4,
			4096, 102400, 100, cpu, 512, null, rodando, null, 51200L, 102400L, null, null, null, null, null, falhas,
			null, null, BigDecimal.valueOf(45), null);
	}

	private HealthGroup health() {
		return service.dashboard().health();
	}

	@Test
	void cada_dimensao_conta_separado_e_o_no_silencioso_nao_vira_suspenso() {
		nos.add(saudavel("nd_ok"));
		// Silencioso: ACTIVE, mas sem heartbeat dentro da janela. Host e rede falham; o runtime se
		// identifica e a aplicação tem perfil — as dimensões não podem afundar juntas.
		nos.add(com("nd_mudo", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_WORKER), NOW.minusSeconds(600), 0, 0, 5.0));
		// Suspenso: heartbeats em dia até a decisão, e agora fora do balanceamento por escolha.
		nos.add(com("nd_susp", NodeStatus.SUSPENDED, Set.of(NodeProfile.NODE_WORKER), NOW, 0, 0, 5.0));

		HealthGroup g = health();

		assertThat(g.countedNodes()).isEqualTo(3);
		assertThat(g.ok()).containsEntry("HOST", 2).containsEntry("RUNTIME", 3);
		assertThat(g.failing()).containsEntry("HOST", 1).containsEntry("NETWORK", 2);
		assertThat(g.degraded().get("APPLICATION")).isZero();
		assertThat(g.ok().get("APPLICATION")).isEqualTo(3);
		assertThat(g.alerts()).extracting(HealthAlert::code).containsExactly("NODE_SILENT", "NODE_SUSPENDED");
		assertThat(g.alertsTotal()).isEqualTo(2);
	}

	@Test
	void no_pendente_de_aprovacao_fica_fora_do_quadro() {
		nos.add(saudavel("nd_ok"));
		nos.add(com("nd_novo", NodeStatus.PENDING_APPROVAL, Set.of(NodeProfile.NODE_WORKER), null, null, 0, null));

		HealthGroup g = health();

		assertThat(g.countedNodes()).isEqualTo(1);
		assertThat(g.byStatus()).containsEntry("PENDING_APPROVAL", 1).containsEntry("ACTIVE", 1);
		assertThat(g.alerts()).isEmpty();
	}

	@Test
	void o_atraso_de_sincronizacao_entra_na_dimensao_de_aplicacao() {
		nos.add(saudavel("nd_em_dia"));
		nos.add(saudavel("nd_atras"));
		// 3 atrás em cada um dos dois tipos de conteúdo = 6 agregados.
		when(versions.countBehind(eq("nd_atras"), org.mockito.ArgumentMatchers.any(ContentKind.class)))
			.thenReturn(3L);

		HealthGroup g = health();

		assertThat(g.degraded()).containsEntry("APPLICATION", 1);
		assertThat(g.ok()).containsEntry("APPLICATION", 1);
		assertThat(g.alerts()).extracting(HealthAlert::nodeId, HealthAlert::code)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("nd_atras", "NODE_BEHIND"));
		assertThat(g.alerts().get(0).reason()).contains("6 agregado(s)");
		assertThat(g.measured()).isTrue();
	}

	@Test
	void sem_o_atraso_consultado_o_quadro_se_declara_incompleto() {
		for (int i = 0; i < NetworkDashboardService.MAX_HEALTH_BEHIND_NODES + 1; i++) {
			nos.add(saudavel("nd_" + i));
		}
		when(versions.countBehind(anyString(), org.mockito.ArgumentMatchers.any(ContentKind.class))).thenReturn(5L);

		HealthGroup g = health();

			// Os primeiros do teto foram consultados (e estão atrás); o resto não foi — e "não
			// consultei" não pode virar "em dia".
		assertThat(g.degraded().get("APPLICATION")).isEqualTo(NetworkDashboardService.MAX_HEALTH_BEHIND_NODES);
		assertThat(g.ok().get("APPLICATION")).isEqualTo(1);
		assertThat(g.measured()).isFalse();
	}

	@Test
	void a_lista_de_alertas_e_ordenada_por_gravidade_e_depois_por_no() {
		nos.add(com("nd_sat_b", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_WORKER), NOW, 4, 0, 10.0));
		nos.add(com("nd_sat_a", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_WORKER), NOW, 4, 0, 10.0));
		nos.add(com("nd_mudo", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_WORKER), NOW.minusSeconds(600), 0, 0, 5.0));
		nos.add(com("nd_susp", NodeStatus.SUSPENDED, Set.of(NodeProfile.NODE_WORKER), NOW, 0, 0, 5.0));

		HealthGroup g = health();

		assertThat(g.alerts()).extracting(HealthAlert::code, HealthAlert::nodeId).containsExactly(
			org.assertj.core.groups.Tuple.tuple("NODE_SILENT", "nd_mudo"),
			org.assertj.core.groups.Tuple.tuple("NODE_SUSPENDED", "nd_susp"),
			org.assertj.core.groups.Tuple.tuple("NODE_FULL", "nd_sat_a"),
			org.assertj.core.groups.Tuple.tuple("NODE_FULL", "nd_sat_b"));
	}

	@Test
	void um_alerta_por_no_com_o_fato_mais_grave() {
		// Suspenso E sem perfil E saturado: o primeiro fato grave é o que tira o nó da rede, e os
		// outros dois não são alertas separados do mesmo nó.
		nos.add(com("nd_muita", NodeStatus.SUSPENDED, Set.of(), NOW, 4, 3, 99.0));

		HealthGroup g = health();

		assertThat(g.alerts()).hasSize(1);
		assertThat(g.alerts().get(0).code()).isEqualTo("NODE_SUSPENDED");
		assertThat(g.alertsTotal()).isEqualTo(1);
		// As dimensões, ainda assim, contam todos os fatos: o alerta é o resumo; o quadro é a medida.
		assertThat(g.failing()).containsEntry("NETWORK", 1).containsEntry("APPLICATION", 1);
		assertThat(g.degraded().get("APPLICATION")).isZero();
	}

	@Test
	void saturado_com_vaga_ainda_e_sobrecarga_e_nao_cheio() {
		// 92% de CPU com uma vaga declarada: o nó ainda aceita, mas está no limite — quem decide o
		// despacho é a vaga; quem avisa o operador é a saturação. Os dois não podem ter o mesmo código.
		nos.add(com("nd_quente", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_WORKER), NOW, 1, 0, 92.0));

		HealthGroup g = health();

		assertThat(g.degraded()).containsEntry("NETWORK", 1);
		assertThat(g.alerts()).extracting(HealthAlert::code).containsExactly("NODE_SATURATED");
		assertThat(g.alerts().get(0).reason()).contains("92.0%");
	}

	@Test
	void saturado_e_sem_vaga_avisa_a_carga_e_nao_a_vaga() {
		// CPU no limiar E sem vaga: os dois fatos são verdade. O código tem de ser o da sobrecarga —
		// "cheio" é a consequência do teto declarado, "saturado" é o que está acontecendo e o que a
		// dimensão de rede já marcou.
		nos.add(com("nd_cheio", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_WORKER), NOW, 4, 0, 95.0));

		HealthGroup g = health();

		assertThat(g.alerts()).extracting(HealthAlert::code).containsExactly("NODE_SATURATED");
	}

	@Test
	void no_com_vaga_e_memoria_no_teto_avisa_a_memoria_e_nao_a_vaga() {
		// Vaga livre (1 de 4), CPU calma, memória no teto que o participante cedeu: é a memória que
		// vai estourar o container, e "tem vaga" não pode esconder isso.
		nos.add(new NetworkNode("nd_mem", "no-nd_mem", "user", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_WORKER),
			"sudeste", "BR", "1.0.0", NOW, 4, 4096, 102400, 100, 10.0, 4096, null, 1, null, 51200L, 102400L, null,
			null, null, null, null, 0, null, null, BigDecimal.valueOf(45), null));

		HealthGroup g = health();

		assertThat(g.alerts()).extracting(HealthAlert::code).containsExactly("NODE_MEMORY_FULL");
		assertThat(g.alerts().get(0).reason()).contains("4096 MiB");
	}

	@Test
	void o_recorte_respeita_o_teto_e_diz_quantos_existem() {
		for (int i = 0; i < NetworkDashboardService.MAX_HEALTH_ALERTS + 5; i++) {
			nos.add(com("nd_sat_" + i, NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_WORKER), NOW, 4, 0, 10.0));
		}

		HealthGroup g = health();

		assertThat(g.alerts()).hasSize(NetworkDashboardService.MAX_HEALTH_ALERTS);
		assertThat(g.alertsTotal()).isEqualTo(NetworkDashboardService.MAX_HEALTH_ALERTS + 5);
	}

	@Test
	void falhas_seguidas_e_cpu_saturada_tem_codigos_e_niveis_proprios() {
		assertThat(NetworkDashboardService.severidade("NODE_SILENT")).isLessThan(
			NetworkDashboardService.severidade("NODE_FAILING"));
		assertThat(NetworkDashboardService.severidade("NODE_FAILING")).isLessThan(
			NetworkDashboardService.severidade("NODE_SATURATED"));
		// Código novo sem classificação não fura a ordem: cai no fim.
		assertThat(NetworkDashboardService.severidade("CODIGO_NOVO")).isEqualTo(4);
	}

	@Test
	void trabalho_sem_quem_faca_vira_um_alerta_de_rede_nomeando_o_tipo() {
		// O painel já dizia "ASYNC_JOB: 0 capazes" na cobertura; agora o operador recebe o alerta
		// com o NÚMERO da fila e sem confundi-lo com problema de um nó (nodeId nulo).
		nos.add(com("nd_media", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA), NOW, 0, 0, 5.0));
		when(tasks.countByStatus(TaskStatus.PENDING)).thenReturn(2L);
		when(tasks.countPendingByType("ASYNC_JOB")).thenReturn(2L);

		HealthGroup g = health();

		assertThat(g.alerts()).extracting(HealthAlert::code).containsExactly("WORK_NO_TARGET");
		assertThat(g.alerts().get(0).nodeId()).isNull();
		assertThat(g.alerts().get(0).reason()).contains("ASYNC_JOB").contains("2 tarefa");
		// Tipo que ALGUÉM pode fazer não alerta, mesmo com fila dele: haveria alerta para toda fila.
		nos.add(com("nd_worker", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_WORKER, NodeProfile.NODE_EDGE), NOW, 0, 0,
			5.0));
		when(tasks.countPendingByType("ASYNC_JOB")).thenReturn(0L);
		when(tasks.countPendingByType("EDGE_CACHE")).thenReturn(4L);
		assertThat(health().alerts()).isEmpty();
		// De volta ao estado do caso: sem o worker capaz e com a fila de ASYNC_JOB.
		when(tasks.countPendingByType("EDGE_CACHE")).thenReturn(0L);
		when(tasks.countPendingByType("ASYNC_JOB")).thenReturn(2L);
		nos.remove(nos.size() - 1);

		// A ordem é estável e o alerta de rede convive com o do nó, só depois dos mais graves.
		nos.add(com("nd_mudo", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA), NOW.minusSeconds(600), 0, 0, 5.0));
		// Dois problemas de rede no mesmo quadro — e aqui o que importa é que os DOIS alertas de rede
		// (um por tipo sem quem faça) podem coexistir sem estourar. O nó novo só pode MEDIA: não
		// muda o silêncio nem a cobertura de ASYNC_JOB/EDGE_CACHE, que é o que este trecho mede.
		nos.add(com("nd_media2", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA), NOW, 0, 0, 5.0));
		when(tasks.countPendingByType("MEDIA_TRANSCODE")).thenReturn(1L);
		// A fila "na rede" é a soma: com o mock zerado, o alerta diria que ASYNC_JOB não tem pendente.
		when(tasks.countByStatus(TaskStatus.PENDING)).thenReturn(3L);
		// A contagem da rede é consultada SÓ quando há fila do tipo: com tipos vazios pelo caminho,
		// toda chamada ao painel viraria uma soma da tabela inteira sem que nada fosse alertado.
		clearInvocations(tasks);
		// MEDIA_TRANSCODE agora tem nó, então só ASYNC_JOB continua sem quem faça: um alerta de rede,
		// e o par (nó sem heartbeat, rede) não estoura na ordenação.
		assertThat(health().alerts()).extracting(HealthAlert::code)
			.containsExactly("NODE_SILENT", "WORK_NO_TARGET");
		when(tasks.countPendingByType("MEDIA_TRANSCODE")).thenReturn(0L);

		// A ordem estável do alerta SEM nó: com dois tipos sem quem faça, o desempate é o código
		// (e o comparador não pode receber nulo direto). Os dois aparecem depois do alerta do nó.
		var semWorker = new java.util.ArrayList<>(nos);
		nos.clear();
		// Nenhum perfil: o nó não pode executar tipo NENHUM — o caso que produz dois alertas de rede.
		nos.add(com("nd_mudo2", NodeStatus.ACTIVE, Set.of(), NOW, 0, 0, 5.0));
		when(tasks.countPendingByType("ASYNC_JOB")).thenReturn(2L);
		when(tasks.countPendingByType("MEDIA_TRANSCODE")).thenReturn(1L);
		clearInvocations(tasks);
		var quadro = health();
		var codigos = quadro.alerts().stream().map(HealthAlert::code).toList();
		// O nó sem perfil também alerta (NODE_NO_WORK, mais grave); o que este trecho mede é que os
		// DOIS alertas de rede existem e não estouram a ordenação.
		assertThat(codigos).containsExactly("NODE_NO_WORK", "WORK_NO_TARGET", "WORK_NO_TARGET");
		assertThat(quadro.alerts()).extracting(HealthAlert::reason)
			.anySatisfy(r -> assertThat(r).contains("ASYNC_JOB"))
			.anySatisfy(r -> assertThat(r).contains("MEDIA_TRANSCODE"));
		// A consulta é POR TIPO (é ela que decide), e a soma da rede só onde há fila: nove dos nove
		// tipos foram perguntados, e a tabela inteira foi somada para nenhum dos seis vazios.
		verify(tasks, times(1)).countPendingByType("READ_QUERY");
		verify(tasks, times(1)).countPendingByType("OBJECT_GET");
		// Dois: um por tipo que tem fila e nenhum nó capaz — `MEDIA_TRANSCODE` e `OBJECT_GET`, que
		// também tem nó sem perfil aqui. Dois, não nove: a tabela inteira não é somada para o tipo
		// cuja fila está vazia.
		verify(tasks, times(2)).countByStatus(TaskStatus.PENDING);
		nos.addAll(semWorker);
		when(tasks.countPendingByType("MEDIA_TRANSCODE")).thenReturn(0L);
	}

	@Test
	void tipo_sem_quem_faca_e_sem_fila_nao_e_alerta() {
		// Nenhum nó pode executar MEDIA_TRANSCODE, e isso não é incidente enquanto não há trabalho:
		// um tipo desligado na rede inteira é configuração, não falta de gente para uma fila.
		nos.add(com("nd_worker", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_WORKER), NOW, 0, 0, 5.0));
		when(tasks.countByStatus(TaskStatus.PENDING)).thenReturn(0L);

		assertThat(health().alerts()).isEmpty();
	}

	@Test
	void a_falha_seguida_aparece_como_aplicacao_e_nao_como_rede() {
		// 3 falhas seguidas (o limite) e ainda com vaga: a máquina responde e participa; quem está
		// com defeito é a execução.
		nos.add(com("nd_falha", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_WORKER), NOW, 0, 3, 10.0));

		HealthGroup g = health();

		assertThat(g.degraded()).containsEntry("APPLICATION", 1);
		assertThat(g.ok()).containsEntry("NETWORK", 1);
		assertThat(g.alerts()).extracting(HealthAlert::code).containsExactly("NODE_FAILING");
		assertThat(g.alerts().get(0).reason()).contains("3 falhas seguidas");
	}
}