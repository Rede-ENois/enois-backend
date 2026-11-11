package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

import com.socialcoisa.rede.network.application.NetworkModeService;
import com.socialcoisa.rede.network.application.NetworkTaskService;
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkMode;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.TaskStatus;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Rede cooperativa, fase 2: a tarefa roda fora da infraestrutura principal sem duplicar efeito.
 *
 * <p>O que estes testes fixam, porque cada um é um jeito de errar:</p>
 * <ul>
 * <li>nó que cai no meio da tarefa → a tarefa volta para a fila e vai para outro nó, com
 * {@code attempt} maior; o resultado que o nó morto devolve depois é <b>descartado</b>, então o
 * efeito acontece uma vez só;</li>
 * <li>resultado de tentativa antiga não sobrescreve o de quem terminou;</li>
 * <li>tarefa vencida não executa e vira DEAD (DLQ visível no painel);</li>
 * <li>perfil não cobre o tipo, e dado sensível não sai para nó de terceiro;</li>
 * <li>nó saturado não recebe tarefa nova;</li>
 * <li>sobrecarga medida vira {@code HIGH_LOAD} e volta a {@code NORMAL} pelos números;</li>
 * <li>a mesma chave de idempotência não cria duas tarefas.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkTasksTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NetworkTaskService tasks;

	@Autowired
	private NetworkModeService modes;

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

	// ---------------------------------------------------------------- cenário central

	/**
	 * O teste que o plano exige: derruba o nó no meio da tarefa e mostra a redistribuição sem
	 * duplicar. A contagem de execução é 1 por efeito — o resultado do nó morto é descartado.
	 */
	@Test
	void noQueCaiNoMeioEhRedistribuidoSemDuplicarEfeito() throws Exception {
		String admin = adminToken();
		Node first = activeNode(admin, "no-a", List.of("NODE_WORKER"));
		Node second = activeNode(admin, "no-b", List.of("NODE_WORKER"));

		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB,
			"job-1", "{\"n\":1}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isEqualTo(1);

		// Um dos dois recebeu; o outro não. Quem recebeu é o "nó que vai cair".
		String assigned = assignedNode(task.id());
		assertThat(assigned).isIn(first.id(), second.id());
		Node dead = assigned.equals(first.id()) ? first : second;
		Node alive = assigned.equals(first.id()) ? second : first;
		assertThat(attemptOf(task.id())).isEqualTo(1);

		// O nó cai: para de mandar heartbeat e o claim vence sem resultado. É o que o relógio faria
		// em produção — sem o heartbeat parado, o scheduler não teria como saber que ele morreu e
		// poderia designar a mesma tarefa a ele de novo.
		stopHeartbeat(dead);
		expireClaim(task.id());
		assertThat(tasks.reclaimAbandoned(Instant.now())).isEqualTo(1);
		assertThat(statusOf(task.id())).isEqualTo("PENDING");

		// A tarefa volta para a fila e vai para o outro nó, com tentativa maior.
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(task.id())).isEqualTo(alive.id());
		assertThat(attemptOf(task.id())).isEqualTo(2);

		// O nó morto "acorda" e devolve o resultado da tentativa 1: descartado, não aplicado.
		mockMvc
			.perform(post("/network/tasks/" + task.id() + "/result").header("X-Node-Id", dead.id())
				.header("X-Node-Key", dead.key())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("taskId", task.id(), "attempt", 1, "status", "DONE",
					"output", "{\"from\":\"no-morto\"}"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.applied").value(false))
			.andExpect(jsonPath("$.reason").value("TASK_ATTEMPT_STALE"));

		// A tarefa continua designada ao nó vivo, sem resultado aplicado.
		assertThat(statusOf(task.id())).isEqualTo("DISPATCHED");
		assertThat(jdbc.queryForObject("select output from network_tasks where id = ?", String.class, task.id()))
			.isNull();

		// O nó vivo conclui a tentativa 2: é o único efeito aplicado.
		mockMvc
			.perform(post("/network/tasks/" + task.id() + "/result").header("X-Node-Id", alive.id())
				.header("X-Node-Key", alive.key())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("taskId", task.id(), "attempt", 2, "status", "DONE",
					"output", "{\"from\":\"no-vivo\"}"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.applied").value(true));

		assertThat(statusOf(task.id())).isEqualTo("DONE");
		assertThat(jdbc.queryForObject("select output from network_tasks where id = ?", String.class, task.id()))
			.contains("no-vivo");
		// Uma execução aplicada, uma descartada: o efeito não dobrou.
		assertThat(jdbc.queryForObject("select count(*) from network_tasks where status = 'DONE'", Integer.class))
			.isEqualTo(1);
	}

	@Test
	void resultadoDeTentativaAntigaNaoSobrescreveODeQuemTerminou() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-unico", List.of("NODE_WORKER"));
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "job-2",
			"{}", DataClass.PUBLIC, null));
		tasks.dispatchPending();

		// Conclui na tentativa 1.
		mockMvc
			.perform(post("/network/tasks/" + task.id() + "/result").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("taskId", task.id(), "attempt", 1, "status", "DONE", "output", "{\"ok\":true}"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.applied").value(true));

		// Reenvio do mesmo resultado: não é erro, e não muda nada.
		mockMvc
			.perform(post("/network/tasks/" + task.id() + "/result").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("taskId", task.id(), "attempt", 1, "status", "DONE", "output", "{\"ok\":true}"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.applied").value(false))
			.andExpect(jsonPath("$.reason").value("TASK_ALREADY_CLAIMED"));
	}

	// ---------------------------------------------------------------- prazo, abandono, DLQ

	@Test
	void tarefaVencidaNaoExecutaEViraDead() throws Exception {
		String admin = adminToken();
		activeNode(admin, "no-a", List.of("NODE_WORKER"));
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "job-3",
			"{}", DataClass.PUBLIC, null));
		tasks.dispatchPending();

		// O prazo vence enquanto o nó executa.
		jdbc.update("update network_tasks set deadline = ? where id = ?",
			java.sql.Timestamp.from(Instant.now().minusSeconds(60)), task.id());
		expireClaim(task.id());

		tasks.reclaimAbandoned(Instant.now());
		assertThat(statusOf(task.id())).isEqualTo("DEAD");
		assertThat(jdbc.queryForObject("select last_error from network_tasks where id = ?", String.class, task.id()))
			.contains("TASK_EXPIRED");
	}

	@Test
	void tarefaQueEsgotaTentativasViraDeadComOErro() throws Exception {
		String admin = adminToken();
		activeNode(admin, "no-a", List.of("NODE_WORKER"));
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "job-4",
			"{}", DataClass.PUBLIC, null));
		tasks.dispatchPending();

		// Simula o nó caindo repetidamente até esgotar as tentativas (default 5).
		for (int i = 0; i < 5; i++) {
			expireClaim(task.id());
			tasks.reclaimAbandoned(Instant.now());
			if ("DEAD".equals(statusOf(task.id()))) {
				break;
			}
			tasks.dispatchPending();
		}
		assertThat(statusOf(task.id())).isEqualTo("DEAD");
		assertThat(jdbc.queryForObject("select last_error from network_tasks where id = ?", String.class, task.id()))
			.contains("TASK_ABANDONED");
	}

	@Test
	void falhaRetentavelVoltaParaAFilaComBackoff() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-a", List.of("NODE_WORKER"));
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "job-5",
			"{}", DataClass.PUBLIC, null));
		tasks.dispatchPending();

		mockMvc
			.perform(post("/network/tasks/" + task.id() + "/result").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("taskId", task.id(), "attempt", 1, "status", "FAILED",
					"error", Map.of("code", "TIMEOUT", "message", "estourou o tempo", "retryable", true)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.applied").value(true));

		assertThat(statusOf(task.id())).isEqualTo("PENDING");
		// O próximo instante é no futuro: o backoff não devolve a tarefa imediatamente.
		assertThat(jdbc.queryForObject("select next_attempt_at from network_tasks where id = ?",
				java.sql.Timestamp.class, task.id()).toInstant())
			.isAfter(Instant.now());
	}

	@Test
	void falhaNaoRetentavelMorreNaHora() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-a", List.of("NODE_WORKER"));
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "job-6",
			"{}", DataClass.PUBLIC, null));
		tasks.dispatchPending();

		mockMvc
			.perform(post("/network/tasks/" + task.id() + "/result").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("taskId", task.id(), "attempt", 1, "status", "FAILED",
					"error", Map.of("code", "BAD_INPUT", "message", "payload invalido", "retryable", false)))))
			.andExpect(status().isOk());

		assertThat(statusOf(task.id())).isEqualTo("FAILED");
	}

	// ---------------------------------------------------------------- permissão e dado

	@Test
	void perfilNaoCobreOTipoDaTarefa() throws Exception {
		String admin = adminToken();
		activeNode(admin, "no-media", List.of("NODE_MEDIA"));
		// ASYNC_JOB exige NODE_WORKER; um nó de mídia não recebe.
		tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "job-7", "{}", DataClass.PUBLIC,
			null));
		assertThat(tasks.dispatchPending()).isZero();
	}

	@Test
	void dadoSensivelNaoSaiParaNoDeTerceiro() throws Exception {
		String admin = adminToken();
		activeNode(admin, "no-worker", List.of("NODE_WORKER"));
		// PERSONAL não pode sair da infraestrutura autorizada; o nó não é NODE_LOCAL.
		tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "job-8", "{}", DataClass.PERSONAL,
			null));
		assertThat(tasks.dispatchPending()).isZero();
	}

	@Test
	void noSaturadoNaoRecebeTarefaNova() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-a", List.of("NODE_WORKER"));
		// CPU observada acima do limiar: saturado, mesmo com vaga declarada.
		heartbeat(node, 95.0, 0);
		tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "job-9", "{}", DataClass.PUBLIC,
			null));
		assertThat(tasks.dispatchPending()).isZero();
	}

	@Test
	void noSemVagaNaoRecebeTarefaNova() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-a", List.of("NODE_WORKER"));
		// Declarou 4 núcleos e reporta 4 tarefas rodando: sem vaga.
		heartbeat(node, 10.0, 4);
		tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "job-10", "{}", DataClass.PUBLIC,
			null));
		assertThat(tasks.dispatchPending()).isZero();
	}

	@Test
	void noNaoVeTarefaDesignadaAOutroNo() throws Exception {
		String admin = adminToken();
		Node first = activeNode(admin, "no-a", List.of("NODE_WORKER"));
		Node second = activeNode(admin, "no-b", List.of("NODE_WORKER"));
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "job-11",
			"{}", DataClass.PUBLIC, null));
		tasks.dispatchPending();

		String assigned = assignedNode(task.id());
		Node owner = assigned.equals(first.id()) ? first : second;
		Node other = assigned.equals(first.id()) ? second : first;

		mockMvc.perform(get("/network/tasks/next").header("X-Node-Id", owner.id())
			.header("X-Node-Key", owner.key())).andExpect(status().isOk()).andExpect(jsonPath("$.tasks.length()").value(1));
		mockMvc.perform(get("/network/tasks/next").header("X-Node-Id", other.id())
			.header("X-Node-Key", other.key())).andExpect(status().isOk()).andExpect(jsonPath("$.tasks.length()").value(0));
	}

	@Test
	void noNaoConcluiTarefaDeOutroNo() throws Exception {
		String admin = adminToken();
		Node first = activeNode(admin, "no-a", List.of("NODE_WORKER"));
		Node second = activeNode(admin, "no-b", List.of("NODE_WORKER"));
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "job-12",
			"{}", DataClass.PUBLIC, null));
		tasks.dispatchPending();

		String assigned = assignedNode(task.id());
		Node other = assigned.equals(first.id()) ? second : first;

		mockMvc
			.perform(post("/network/tasks/" + task.id() + "/result").header("X-Node-Id", other.id())
				.header("X-Node-Key", other.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("taskId", task.id(), "attempt", 1, "status", "DONE", "output", "{}"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("TASK_NOT_ALLOWED"));
	}

	// ---------------------------------------------------------------- idempotência e cancelamento

	@Test
	void mesmaChaveDeIdempotenciaNaoCriaDuasTarefas() {
		NetworkTask first = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "job-13",
			"{}", DataClass.PUBLIC, null));
		NetworkTask second = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "job-13",
			"{}", DataClass.PUBLIC, null));

		assertThat(second.id()).isEqualTo(first.id());
		assertThat(jdbc.queryForObject("select count(*) from network_tasks", Integer.class)).isEqualTo(1);
	}

	@Test
	void adminCancelaTarefaNaFila() throws Exception {
		String admin = adminToken();
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "job-14",
			"{}", DataClass.PUBLIC, null));

		mockMvc.perform(post("/admin/network/tasks/" + task.id() + "/cancel").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"));

		// Cancelar de novo é 409: já terminou.
		mockMvc.perform(post("/admin/network/tasks/" + task.id() + "/cancel").header("X-Session-Token", admin))
			.andExpect(status().isConflict());
	}

	@Test
	void painelMostraADlqEOModo() throws Exception {
		String admin = adminToken();
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "job-15",
			"{}", DataClass.PUBLIC, null));
		jdbc.update("update network_tasks set status = 'DEAD', last_error = 'TASK_ABANDONED' where id = ?",
			task.id());

		mockMvc.perform(get("/admin/network/tasks/overview").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.dead").value(1))
			.andExpect(jsonPath("$.mode").value("NORMAL"));
		mockMvc.perform(get("/admin/network/tasks").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].lastError").value("TASK_ABANDONED"));
	}

	// ---------------------------------------------------------------- credencial e painel

	@Test
	void rotaDeTarefaSemCredencialDeNoEh401() throws Exception {
		mockMvc.perform(get("/network/tasks/next")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/network/tasks/tk_qualquer/result").contentType(MediaType.APPLICATION_JSON)
			.content("{\"taskId\":\"tk_qualquer\",\"attempt\":1,\"status\":\"DONE\"}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void apenasAdminVeAsTarefas() throws Exception {
		var member = auth.authenticate("net_task_member");

		mockMvc.perform(get("/admin/network/tasks").header("X-Session-Token", member.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/network/tasks/overview").header("X-Session-Token", member.token()))
			.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- sobrecarga medida

	@Test
	void sobrecargaMedidaEntraEmHighLoadEVoltaAoNormal() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-a", List.of("NODE_WORKER"));
		// 4 núcleos, 0 rodando: 4 vagas. Cinco tarefas na fila passam da capacidade.
		for (int i = 0; i < 5; i++) {
			tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "load-" + i, "{}",
				DataClass.PUBLIC, null));
		}
		assertThat(modes.evaluate()).isEqualTo(NetworkMode.HIGH_LOAD);

		// A fila esvazia (as tarefas foram designadas): volta a NORMAL pelo número medido.
		jdbc.update("update network_tasks set status = 'DONE'");
		assertThat(modes.evaluate()).isEqualTo(NetworkMode.NORMAL);
	}

	@Test
	void modoDeIncidenteNaoEhSobrescritoPelaAutomacao() {
		jdbc.update("insert into network_state (id, mode, reason, since) values ('global', 'EMERGENCY', 'teste', ?)",
			java.sql.Timestamp.from(Instant.now()));
		assertThat(modes.evaluate()).isEqualTo(NetworkMode.EMERGENCY);
	}

	// ---------------------------------------------------------------- helpers

	private record Node(String id, String key) {
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_task_admin");
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
		heartbeat(node, 5.0, 0);
		return node;
	}

	private void heartbeat(Node node, double cpuPercent, int runningTasks) throws Exception {
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
					Map.of("cpuPercent", cpuPercent, "memoryMb", 1024, "runningTasks", runningTasks)))))
			.andExpect(status().isOk());
	}

	/** O nó parou de mandar heartbeat: o último visto fica para trás do limiar de nó vivo. */
	private void stopHeartbeat(Node node) {
		jdbc.update("update network_nodes set last_seen_at = ? where id = ?",
			java.sql.Timestamp.from(Instant.now().minusSeconds(600)), node.id());
	}

	private void expireClaim(String taskId) {
		jdbc.update("update network_tasks set claim_expires_at = ? where id = ?",
			java.sql.Timestamp.from(Instant.now().minusSeconds(5)), taskId);
	}

	private String assignedNode(String taskId) {
		return jdbc.queryForObject("select assigned_node_id from network_tasks where id = ?", String.class, taskId);
	}

	private int attemptOf(String taskId) {
		return jdbc.queryForObject("select attempt from network_tasks where id = ?", Integer.class, taskId);
	}

	private String statusOf(String taskId) {
		return jdbc.queryForObject("select status from network_tasks where id = ?", String.class, taskId);
	}
}
