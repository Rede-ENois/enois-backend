package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.network.application.NetworkTaskService;
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fase 6, recuperação regional: a região afetada sai, e os nós das outras atendem o que podem.
 *
 * <p>Até esta rodada a região era <b>só</b> afinidade de ordenação ({@code regionPenalty}, ADR-012):
 * nada no Control Plane podia tirar uma região de operação. Numa queda regional os nós dela
 * continuam {@code ACTIVE} — não foram suspensos, a região é que saiu — então a rede seguia
 * designando tarefa para eles, e a tarefa presa voltava por claim vencido e era designada de novo
 * ao mesmo nó, em loop, até morrer de tentativas. A drenagem por status não cobria o caso: ela
 * pergunta por {@code n.status <> 'ACTIVE'}.</p>
 *
 * <p>Os testes abaixo fixam as quatro portas que a declaração fecha: despacho, capacidade, heartbeat
 * e drenagem — mais a volta e o motivo obrigatório. O que se declara é uma decisão humana de
 * incidente, com motivo auditado; nenhum número medido distingue "região em desastre" de "região
 * lenta".</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkRegionTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NetworkTaskService tasks;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_region_states");
		jdbc.update("delete from network_tasks");
		jdbc.update("delete from network_state");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
		jdbc.update("delete from audit_logs where action like 'NETWORK_REGION_%' "
				+ "or action = 'NETWORK_TASK_REGION_EVACUATED'");
	}

	// ---------------------------------------------------------------- a região sai

	/**
	 * Declarar a região devolve à fila, na hora, o que estava designado aos nós dela — e a tarefa
	 * passa a ser atendida por outra região.
	 *
	 * <p>O claim é longo de propósito: sem a drenagem regional, a rodada seguinte não designaria
	 * nada, porque a tarefa estaria bloqueada por um nó que não vai responder — e o loop real
	 * (devolvida por claim vencido, designada ao mesmo nó) só apareceria dois minutos depois.</p>
	 */
	@Test
	void declararARegiaoDevolveATarefaEDesignaParaOutraRegiao() throws Exception {
		String admin = adminToken();
		Node afetado = activeNode(admin, "no-sudeste", List.of("NODE_WORKER"), "sudeste");

		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "regiao-1",
			"{}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(task.id())).isEqualTo(afetado.id());

		// Outra região, com nó saudável: é quem pode atender enquanto a afetada está fora.
		Node saudavel = activeNode(admin, "no-sul", List.of("NODE_WORKER"), "sul");

		declare(admin, "sudeste", "queda de energia na regiao");

		assertThat(tasks.drainEvacuatedRegions()).isEqualTo(1);
		assertThat(statusOf(task.id())).isEqualTo("PENDING");
		assertThat(assignedNode(task.id())).isNull();

		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(task.id())).isEqualTo(saudavel.id());
		// A tentativa sobe na nova designação: o resultado do nó evacuado, se chegar, cai no descarte
		// por tentativa que já existe. Drenar cedo não abre porta para efeito duplo.
		assertThat(attemptOf(task.id())).isEqualTo(2);
	}

	/**
	 * Sem nó em outra região, a tarefa espera na fila em vez de voltar para a região evacuada.
	 *
	 * <p>É o defeito exato que a evacuação rompe: antes, o claim vencia, {@code reclaimAbandoned}
	 * devolvia, e {@code choose()} designava ao mesmo nó da região caída outra vez — até a tarefa
	 * morrer de tentativas. Agora o nó da região evacuada não é candidato.</p>
	 */
	@Test
	void semOutraRegiaoATarefaFicaNaFilaEmVezDeVoltarParaARegiaoEvacuada() throws Exception {
		String admin = adminToken();
		Node afetado = activeNode(admin, "no-unico", List.of("NODE_WORKER"), "sudeste");
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "regiao-2",
			"{}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isEqualTo(1);

		declare(admin, "sudeste", "queda regional");
		tasks.drainEvacuatedRegions();

		assertThat(tasks.dispatchPending()).isZero();
		assertThat(statusOf(task.id())).isEqualTo("PENDING");
		assertThat(assignedNode(task.id())).isNull();
		assertThat(jdbc.queryForObject("select count(*) from network_tasks where assigned_node_id = ?",
			Integer.class, afetado.id())).isZero();
	}

	/**
	 * A região é comparada normalizada: o nó declarou "Sudeste" e a evacuação veio " SUDESTE ". Sem a
	 * normalização (V289), seriam duas regiões para o evacuador e uma para o balanceador — o nó
	 * receberia tarefa de uma região declarada caída.
	 */
	@Test
	void evacuacaoIgnoraCaixaEEspacosDaRegiao() throws Exception {
		String admin = adminToken();
		Node afetado = activeNode(admin, "no-grafia", List.of("NODE_WORKER"), "Sudeste");
		Node outro = activeNode(admin, "no-grafia-outro", List.of("NODE_WORKER"), "Sul");

		declare(admin, "  SUDESTE  ", "mesma regiao, outra grafia");

		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "regiao-3",
			"{}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(task.id())).isEqualTo(outro.id());
		assertThat(assignedNode(task.id())).isNotEqualTo(afetado.id());
	}

	// ---------------------------------------------------------------- o nó da região não pede

	/**
	 * O heartbeat responde {@code acceptTasks=false} ao nó da região evacuada, e a rota de tarefas
	 * devolve lista vazia mesmo com trabalho designado a ele.
	 *
	 * <p>São duas portas de propósito. O heartbeat é o canal principal — drenar é não pedir, sem 403
	 * que encerraria o reporte — e a lista vazia cobre o runtime que insiste em pedir ou que tem o
	 * heartbeat em cache.</p>
	 */
	@Test
	void noDaRegiaoEvacuadaNaoAceitaNemRecebeTarefa() throws Exception {
		String admin = adminToken();
		Node afetado = activeNode(admin, "no-nao-pede", List.of("NODE_WORKER"), "sudeste");
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "regiao-4",
			"{}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(task.id())).isEqualTo(afetado.id());

		declare(admin, "sudeste", "incidente regional");

		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", afetado.id())
				.header("X-Node-Key", afetado.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.acceptTasks").value(false));

		// A tarefa ainda está DISPATCHED a ele (a drenagem não rodou): a lista vazia é a segunda
		// porta, não consequência da drenagem.
		assertThat(statusOf(task.id())).isEqualTo("DISPATCHED");
		assertThat(tasksOf(afetado).size()).isZero();
	}

	/** Nó de região em operação continua recebendo `true`: evacuar uma região não para a rede. */
	@Test
	void noDeRegiaoEmOperacaoContinuaAceitando() throws Exception {
		String admin = adminToken();
		Node outro = activeNode(admin, "no-aceita", List.of("NODE_WORKER"), "sul");
		declare(admin, "sudeste", "incidente regional");

		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", outro.id())
				.header("X-Node-Key", outro.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.acceptTasks").value(true));
	}

	// ---------------------------------------------------------------- a capacidade não conta a vaga

	/**
	 * O nó da região evacuada não promete vaga: o painel mostra a capacidade livre caindo e a região
	 * na lista das que estão fora.
	 *
	 * <p>Sem isso o modo diria "vagas=4" com a região inteira caída e a razão fila/vagas pareceria
	 * folgada justamente durante o incidente — o mesmo defeito que excluir nó saturado e em
	 * quarentena já corrigiu.</p>
	 */
	@Test
	void regiaoEvacuadaNaoContaCapacidadeEPainelDizQualRegiao() throws Exception {
		String admin = adminToken();
		activeNode(admin, "no-capacidade", List.of("NODE_WORKER"), "sudeste");

		mockMvc.perform(get("/admin/network/nodes/dashboard").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.freeSlots").value(4))
			.andExpect(jsonPath("$.outOfServiceRegions").isEmpty());

		declare(admin, "sudeste", "incidente regional");

		mockMvc.perform(get("/admin/network/nodes/dashboard").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.freeSlots").value(0))
			.andExpect(jsonPath("$.outOfServiceRegions[0]").value("sudeste"));
	}

	/**
	 * O painel de tarefas mostra quantas estão presas à região evacuada antes da drenagem: entre a
	 * declaração e o ciclo seguinte, o número é a diferença entre "nada aconteceu" e "há trabalho
	 * parado".
	 */
	@Test
	void painelMostraAsTarefasAguardandoEvacuacao() throws Exception {
		String admin = adminToken();
		activeNode(admin, "no-painel-regiao", List.of("NODE_WORKER"), "sudeste");
		tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "regiao-painel-1", "{}",
			DataClass.PUBLIC, null));
		tasks.dispatchPending();

		mockMvc.perform(get("/admin/network/tasks/overview").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.awaitingEvacuation").value(0));

		declare(admin, "sudeste", "incidente regional");
		mockMvc.perform(get("/admin/network/tasks/overview").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.awaitingEvacuation").value(1));

		tasks.drainEvacuatedRegions();
		mockMvc.perform(get("/admin/network/tasks/overview").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.awaitingEvacuation").value(0));
	}

	// ---------------------------------------------------------------- tarefa com alvo

	/**
	 * Tarefa com <b>alvo</b> na região evacuada volta para a fila e <b>não</b> é executada por outro
	 * nó: alvo é vínculo (V287), não preferência — o estado daquele trabalho vive naquele nó.
	 * Executar no nó errado não é executar pior, é não executar.
	 */
	@Test
	void tarefaComAlvoNaRegiaoEvacuadaNaoRodaEmOutroNo() throws Exception {
		String admin = adminToken();
		Node alvo = activeNode(admin, "no-alvo", List.of("NODE_WORKER"), "sudeste");
		activeNode(admin, "no-livre", List.of("NODE_WORKER"), "sul");

		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "regiao-5",
			"{}", DataClass.PUBLIC, null, Instant.now().plusSeconds(600), alvo.id()));
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(task.id())).isEqualTo(alvo.id());

		declare(admin, "sudeste", "incidente regional");
		assertThat(tasks.drainEvacuatedRegions()).isEqualTo(1);

		assertThat(tasks.dispatchPending()).isZero();
		assertThat(statusOf(task.id())).isEqualTo("PENDING");
		assertThat(assignedNode(task.id())).isNull();
	}

	// ---------------------------------------------------------------- a região volta

	/**
	 * A volta é humana e devolve a região à operação: o nó recebe `acceptTasks=true` de novo e o
	 * despacho volta a escolhê-lo. Readmitir por relógio seria a automação desfazendo decisão de
	 * incidente.
	 */
	@Test
	void limparADeclaracaoDevolveARegiaoAOperacao() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-volta", List.of("NODE_WORKER"), "sudeste");
		declare(admin, "sudeste", "incidente regional");

		mockMvc
			.perform(delete("/admin/network/regions/sudeste").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));

		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.acceptTasks").value(true));

		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "regiao-6",
			"{}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(task.id())).isEqualTo(node.id());
	}

	/** Limpar o que não estava declarado é 404 confesso: a tela não acha que liberou algo. */
	@Test
	void limparRegiaoQueNaoEstavaForaResponde404() throws Exception {
		String admin = adminToken();

		mockMvc.perform(delete("/admin/network/regions/sudeste").header("X-Session-Token", admin))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NETWORK_REGION_NOT_DECLARED"));
	}

	// ---------------------------------------------------------------- motivo obrigatório e auditoria

	/**
	 * Sem motivo não há declaração. Tirar uma região do ar é decisão de incidente, e o motivo é o que
	 * sobra para quem investiga depois.
	 */
	@Test
	void declaracaoSemMotivoERecusada() throws Exception {
		String admin = adminToken();

		mockMvc
			.perform(post("/admin/network/regions").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("region", "sudeste"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NETWORK_REGION_REASON_REQUIRED"));

		mockMvc
			.perform(post("/admin/network/regions").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", "sem regiao"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NETWORK_REGION_REQUIRED"));

		mockMvc.perform(get("/admin/network/regions").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	/** A declaração é auditada com ator e motivo; a volta também. */
	@Test
	void declaracaoEVoltaSaoAuditadas() throws Exception {
		String admin = adminToken();

		declare(admin, "sudeste", "queda \"total\" na regiao");

		String metadata = jdbc.queryForObject(
			"select metadata from audit_logs where action = 'NETWORK_REGION_OUT_OF_SERVICE' and target_id = ?",
			String.class, "sudeste");
		// O motivo entra em metadata JSON: aspas do texto humano não podem quebrar o JSON.
		assertThat(metadata).contains("queda \\\"total\\\" na regiao");

		mockMvc.perform(delete("/admin/network/regions/sudeste").header("X-Session-Token", admin))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForObject(
			"select count(*) from audit_logs where action = 'NETWORK_REGION_BACK_IN_SERVICE' and target_id = ?",
			Integer.class, "sudeste")).isEqualTo(1);
	}

	/** Redeclarar não reinicia o incidente: `since` é quando a região ENTROU na contingência. */
	@Test
	void redeclararNaoReiniciaOSince() throws Exception {
		String admin = adminToken();
		declare(admin, "sudeste", "primeira declaracao");
		Instant first = jdbc.queryForObject("select declared_at from network_region_states where region = ?",
			java.sql.Timestamp.class, "sudeste").toInstant();

		Thread.sleep(20);
		declare(admin, "sudeste", "segunda declaracao");

		Instant second = jdbc.queryForObject("select declared_at from network_region_states where region = ?",
			java.sql.Timestamp.class, "sudeste").toInstant();
		assertThat(second).isEqualTo(first);
	}

	// ---------------------------------------------------------------- helpers

	private record Node(String id, String key) {
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_region_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private void declare(String admin, String region, String reason) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("region", region);
		body.put("reason", reason);
		mockMvc
			.perform(post("/admin/network/regions").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isOk());
	}

	private Node activeNode(String admin, String name, List<String> profiles, String region) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("displayName", name, "ownerUserId", "owner-" + name, "profiles", profiles))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
			.get("token")
			.asString();

		Map<String, Object> enrollBody = new HashMap<>();
		enrollBody.put("provisionToken", provisionToken);
		enrollBody.put("declared",
			Map.of("cpuCores", 4, "memoryMb", 8192, "diskMb", 51200, "bandwidthMbps", 100));
		// Na forma aninhada do contrato (03-contratos.md), como o instalador grava.
		enrollBody.put("location", Map.of("region", region, "country", "BR"));

		var enrollResult = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(enrollBody)))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(enrollResult.getResponse().getContentAsString()).get("nodeId")
			.asString();

		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());

		var keyResult = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		String key = objectMapper.readTree(keyResult.getResponse().getContentAsString()).get("apiKey").asString();

		Node node = new Node(nodeId, key);
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
					Map.of("cpuPercent", 5.0, "memoryMb", 1024, "runningTasks", 0)))))
			.andExpect(status().isOk());
		return node;
	}

	private JsonNode tasksOf(Node node) throws Exception {
		var result = mockMvc
			.perform(get("/network/tasks/next").header("X-Node-Id", node.id()).header("X-Node-Key", node.key()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("tasks");
	}

	private String assignedNode(String taskId) {
		return jdbc.queryForObject("select assigned_node_id from network_tasks where id = ?", String.class, taskId);
	}

	private String statusOf(String taskId) {
		return jdbc.queryForObject("select status from network_tasks where id = ?", String.class, taskId);
	}

	private int attemptOf(String taskId) {
		return jdbc.queryForObject("select attempt from network_tasks where id = ?", Integer.class, taskId);
	}
}
