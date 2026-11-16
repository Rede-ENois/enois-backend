package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import tools.jackson.databind.ObjectMapper;

/**
 * Fase 4, drenagem: tirar um nó de circulação não pode custar a tarefa que está na mão dele.
 *
 * <p>O cenário é o da operação real: o admin suspende o nó (manutenção, suspeita, incidente). O nó
 * para de receber heartbeat válido — {@code NODE_SUSPENDED}. O que estava designado a ele continua
 * com {@code claim} vigente, e ninguém mais pode recolhê-lo: {@code findAbandoned} só devolve claim
 * <b>vencido</b>. Se a tarefa estiver a dez segundos do vencimento, ela espera dez segundos; se o
 * claim tiver mais dois minutos pela frente (o padrão), a fila fica dois minutos parada esperando um
 * prazo que ninguém vai cumprir.</p>
 *
 * <p>Na drenagem, a tarefa volta para a fila <b>na hora</b> e o {@code attempt} sobe — o resultado
 * que o nó morto eventualmente mandar é descartado pela regra de tentativa que já existe, então
 * antecipar a devolução não duplica efeito.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkDrainTests {

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
		jdbc.update("delete from network_tasks");
		jdbc.update("delete from network_state");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	// ---------------------------------------------------------------- suspensão drena

	/**
	 * Suspender o nó devolve à fila, na hora, o que estava designado a ele.
	 *
	 * <p>Antes desta rodada a tarefa esperava o claim vencer (dois minutos por padrão) com o nó já
	 * proibido de autenticar — espera que não pode terminar bem. O teste dá ao claim um prazo
	 * confortável de propósito: se a drenagem não existisse, a rodada seguinte não designaria nada,
	 * porque a tarefa estaria bloqueada por um nó que não vai responder.</p>
	 */
	@Test
	void suspenderONoDevolveATarefaEmCursoParaAFila() throws Exception {
		String admin = adminToken();
		Node draining = activeNode(admin, "no-a-drenar", List.of("NODE_WORKER"));

		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "drain-1",
			"{}", DataClass.PUBLIC, null));
		// Designa com o nó a drenar sendo o único elegível: o cenário é "tarefa na mão dele", não uma
		// aposta em qual nó o balanceamento escolhe.
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(task.id())).isEqualTo(draining.id());

		// Um nó saudável entra depois, para provar que a tarefa drenada encontra quem a execute.
		Node healthy = activeNode(admin, "no-saudavel", List.of("NODE_WORKER"));

		// Suspensão: o nó perde o direito de operar, mas o claim continua vigente por dois minutos.
		mockMvc.perform(post("/admin/network/nodes/" + draining.id() + "/status")
			.header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("status", "SUSPENDED"))))
			.andExpect(status().isOk());

		// Drenagem: a tarefa sai do nó suspenso e vai para quem pode executar.
		tasks.drainSuspendedNodes();

		assertThat(statusOf(task.id())).isEqualTo("PENDING");
		assertThat(assignedNode(task.id())).isNull();

		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(task.id())).isEqualTo(healthy.id());
		// A tentativa sobe na nova designação — é o claim que incrementa. O resultado do nó suspenso,
		// se chegar, encontra a tarefa DISPATCHED a outro nó com attempt diferente e cai no descarte
		// por tentativa que já existe: drenar cedo não abre porta para efeito duplo.
		assertThat(attemptOf(task.id())).isEqualTo(2);
	}

	/** A drenagem é auditada: o painel conta a história inteira, com o nó de origem. */
	@Test
	void drenagemEAuditadaComONoDeOrigem() throws Exception {
		String admin = adminToken();
		Node draining = activeNode(admin, "no-auditar", List.of("NODE_WORKER"));
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "drain-2",
			"{}", DataClass.PUBLIC, null));
		tasks.dispatchPending();

		jdbc.update("update network_nodes set status = 'SUSPENDED' where id = ?", draining.id());
		tasks.drainSuspendedNodes();

		String metadata = jdbc.queryForObject(
			"select metadata from audit_logs where action = 'NETWORK_TASK_DRAINED' and target_id = ?",
			String.class, task.id());
		assertThat(metadata).contains(draining.id());
	}

	/**
	 * Nó saudável não é tocado: drenar todo mundo por suspeita não é drenagem, é parar a rede.
	 */
	@Test
	void drenagemNaoMexeNoQueEstaEmNoQueOpera() throws Exception {
		String admin = adminToken();
		Node healthy = activeNode(admin, "no-ativo", List.of("NODE_WORKER"));
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "drain-3",
			"{}", DataClass.PUBLIC, null));
		tasks.dispatchPending();
		assertThat(assignedNode(task.id())).isEqualTo(healthy.id());

		assertThat(tasks.drainSuspendedNodes()).isZero();
		assertThat(statusOf(task.id())).isEqualTo("DISPATCHED");
		assertThat(assignedNode(task.id())).isEqualTo(healthy.id());
	}

	/**
	 * O painel mostra quantas tarefas estão presas a nó fora de operação enquanto a drenagem não
	 * roda: entre a suspensão e o ciclo seguinte, o número é a diferença entre "nada aconteceu" e
	 * "há trabalho parado".
	 */
	@Test
	void painelMostraAsTarefasAguardandoDrenagem() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-painel-drenagem", List.of("NODE_WORKER"));
		tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "drain-painel-1", "{}",
			DataClass.PUBLIC, null));
		tasks.dispatchPending();

		mockMvc.perform(get("/admin/network/tasks/overview").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.awaitingDrain").value(0));

		jdbc.update("update network_nodes set status = 'SUSPENDED' where id = ?", node.id());
		mockMvc.perform(get("/admin/network/tasks/overview").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.awaitingDrain").value(1));

		tasks.drainSuspendedNodes();
		mockMvc.perform(get("/admin/network/tasks/overview").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.awaitingDrain").value(0));
	}

	// ---------------------------------------------------------------- helpers

	private record Node(String id, String key) {
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_drain_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private Node activeNode(String admin, String name, List<String> profiles) throws Exception {
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

		var enrollResult = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 4, "memoryMb", 8192, "diskMb", 51200, "bandwidthMbps", 100)))))
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
		Map<String, Object> body = new HashMap<>();
		body.put("runtimeVersion", "0.1.0");
		body.put("observed", Map.of("cpuPercent", 5.0, "memoryMb", 1024, "runningTasks", 0));
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isOk());
		return node;
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
