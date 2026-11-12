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
import com.socialcoisa.rede.network.application.NodeHealthWorker;
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkMode;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Rede cooperativa, fase 3: balanceamento por saúde medida.
 *
 * <p>O critério do plano é "nó com latência/erro perde prioridade; remoção temporária". O que estes
 * testes fixam, porque cada um é um jeito de errar:</p>
 * <ul>
 * <li>nó que erra <b>seguido</b> sai do balanceamento por uma janela — e não volta a receber nem
 * sendo o único da rede;</li>
 * <li>erro isolado não condena: o streak é o sinal, não o erro;</li>
 * <li>um sucesso zera o streak, senão o nó carregaria erro velho para sempre;</li>
 * <li>nó lento perde prioridade mas continua recebendo quando é o que sobrou — remover seria
 * desproporcional;</li>
 * <li>janela vencida devolve o nó sozinho, com auditoria da volta;</li>
 * <li>nó fora do balanceamento não conta vaga no modo da rede;</li>
 * <li>o painel mostra o nó degradado e o motivo da remoção.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkBalancingTests {

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

	@Autowired
	private NodeHealthWorker health;

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

	// ---------------------------------------------------------------- remoção por erro

	/**
	 * O teste que o plano exige: o nó doente sai do balanceamento.
	 *
	 * <p>O cenário é montado para que o código ANTIGO escolhesse o nó doente de forma determinística:
	 * ele tem mais folga (4 vagas livres contra 1), então a ordenação por folga o colocaria em
	 * primeiro. As asserções de Q0 logo antes da designação provam que ele passa por todos os filtros
	 * que existiam antes desta rodada — vivo, com perfil, com vaga, sem saturação. Só o filtro novo o
	 * exclui.</p>
	 */
	@Test
	void noComFalhasSeguidasSaiDoBalanceamento() throws Exception {
		String admin = adminToken();
		Node sick = activeNode(admin, "no-doente", List.of("NODE_WORKER"));
		Node healthy = activeNode(admin, "no-saudavel", List.of("NODE_WORKER"));
		// O saudável está quase cheio: folga 1/4. O doente tem 4/4. Sem saúde no critério, o doente
		// ganharia sempre.
		heartbeat(healthy, 5.0, 3);

		failThreeTimes(sick);

		// Q0: o nó doente continua passando por TODOS os filtros anteriores a esta rodada.
		NetworkNode before = node(sick.id());
		assertThat(before.alive(Instant.now(), java.time.Duration.ofSeconds(180))).isTrue();
		assertThat(before.accepts(TaskType.ASYNC_JOB, DataClass.PUBLIC)).isTrue();
		assertThat(before.hasCapacity()).isTrue();
		assertThat(before.saturated()).isFalse();
		assertThat(before.consecutiveFailures()).isEqualTo(NetworkNode.FAILURE_STREAK_LIMIT);
		assertThat(before.quarantined(Instant.now())).isTrue();

		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "balance-1",
			"{}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(task.id())).isEqualTo(healthy.id());
	}

	/** Um erro não condena: o nó continua recebendo, e continua sendo o preferido pela folga. */
	@Test
	void falhaIsoladaNaoTiraONoDoBalanceamento() throws Exception {
		String admin = adminToken();
		Node flaky = activeNode(admin, "no-instavel", List.of("NODE_WORKER"));
		Node other = activeNode(admin, "no-outro", List.of("NODE_WORKER"));
		heartbeat(other, 5.0, 3);

		failOnce(flaky, "balance-2a");
		failOnce(flaky, "balance-2b");

		assertThat(node(flaky.id()).consecutiveFailures()).isEqualTo(2);
		assertThat(node(flaky.id()).quarantined(Instant.now())).isFalse();

		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "balance-2c",
			"{}", DataClass.PUBLIC, null));
		tasks.dispatchPending();
		assertThat(assignedNode(task.id())).isEqualTo(flaky.id());
	}

	/**
	 * Um sucesso zera o streak. Sem isso, o nó acumularia erro de ontem e seria removido hoje por um
	 * defeito que já não existe.
	 */
	@Test
	void sucessoZeraOStreakDeFalha() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-recuperado", List.of("NODE_WORKER"));

		failOnce(node, "balance-3a");
		failOnce(node, "balance-3b");
		completeSuccessfully(node, "balance-3c");
		assertThat(node(node.id()).consecutiveFailures()).isZero();

		failOnce(node, "balance-3d");
		failOnce(node, "balance-3e");
		assertThat(node(node.id()).consecutiveFailures()).isEqualTo(2);
		assertThat(node(node.id()).quarantined(Instant.now())).isFalse();
	}

	/** Nó removido não recebe tarefa nem sendo o único: a tarefa espera em vez de queimar tentativa. */
	@Test
	void noRemovidoNaoRecebeTarefaMesmoSendoOUnico() throws Exception {
		String admin = adminToken();
		Node only = activeNode(admin, "no-unico", List.of("NODE_WORKER"));
		failThreeTimes(only);

		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "balance-4",
			"{}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isZero();
		assertThat(statusOf(task.id())).isEqualTo("PENDING");
		assertThat(assignedNode(task.id())).isNull();
	}

	// ---------------------------------------------------------------- prioridade por latência

	/**
	 * Nó lento perde prioridade, mas não é removido: ele ainda entrega, e tirá-lo do ar seria
	 * desproporcional. Com um nó rápido disponível, o rápido recebe; sem ele, o lento recebe.
	 */
	@Test
	void noLentoPerdePrioridadeMasContinuaRecebendo() throws Exception {
		String admin = adminToken();
		Node slow = activeNode(admin, "no-lento", List.of("NODE_WORKER"));
		Node fast = activeNode(admin, "no-rapido", List.of("NODE_WORKER"));
		heartbeat(fast, 5.0, 3);
		jdbc.update("update network_nodes set observed_latency_ms = ? where id = ?",
			NetworkNode.SLOW_LATENCY_MS + 30_000, slow.id());

		NetworkTask first = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "balance-5a",
			"{}", DataClass.PUBLIC, null));
		tasks.dispatchPending();
		assertThat(assignedNode(first.id())).isEqualTo(fast.id());

		// O rápido enche: sobra o lento, que recebe mesmo sendo lento.
		heartbeat(fast, 5.0, 4);
		NetworkTask second = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "balance-5b",
			"{}", DataClass.PUBLIC, null));
		tasks.dispatchPending();
		assertThat(assignedNode(second.id())).isEqualTo(slow.id());
	}

	/** A latência medida entra no nó quando o resultado é aplicado: é o que o painel mostra. */
	@Test
	void conclusaoGravaALatenciaMedida() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-medido", List.of("NODE_WORKER"));
		// A designação aconteceu 2s atrás: a latência medida tem de refletir isso, não zero.
		jdbc.update("update network_nodes set observed_latency_ms = null where id = ?", node.id());

		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "balance-6",
			"{}", DataClass.PUBLIC, null));
		tasks.dispatchPending();
		jdbc.update("update network_tasks set dispatched_at = ? where id = ?",
			java.sql.Timestamp.from(Instant.now().minusSeconds(2)), task.id());

		report(node, task.id(), 1, "DONE", null);

		Integer latency = jdbc.queryForObject("select observed_latency_ms from network_nodes where id = ?",
			Integer.class, node.id());
		assertThat(latency).isNotNull();
		assertThat(latency).isGreaterThanOrEqualTo(2000);
	}

	// ---------------------------------------------------------------- volta ao balanceamento

	/** A janela vence e o nó volta sozinho, com auditoria da volta e streak limpo. */
	@Test
	void janelaVencidaDevolveONoAoBalanceamento() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-volta", List.of("NODE_WORKER"));
		failThreeTimes(node);
		assertThat(node(node.id()).quarantined(Instant.now())).isTrue();

		// O tempo passa: a janela venceu.
		jdbc.update("update network_nodes set quarantined_until = ? where id = ?",
			java.sql.Timestamp.from(Instant.now().minusSeconds(5)), node.id());

		assertThat(health.runOnce()).isEqualTo(1);
		NetworkNode released = node(node.id());
		assertThat(released.quarantined(Instant.now())).isFalse();
		assertThat(released.quarantineReason()).isNull();
		assertThat(released.consecutiveFailures()).isZero();
		assertThat(auditCount("NETWORK_NODE_RELEASED", node.id())).isEqualTo(1);

		// E volta a receber trabalho.
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "balance-7",
			"{}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(task.id())).isEqualTo(node.id());
	}

	/** A remoção é auditada na entrada, com o número que a motivou. */
	@Test
	void remocaoEAuditadaComONumeroQueAMotivou() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-auditado", List.of("NODE_WORKER"));
		failThreeTimes(node);

		assertThat(auditCount("NETWORK_NODE_QUARANTINED", node.id())).isEqualTo(1);
		String metadata = jdbc.queryForObject(
			"select metadata from audit_logs where action = 'NETWORK_NODE_QUARANTINED' and target_id = ?",
			String.class, node.id());
		assertThat(metadata).contains("FAILURE_STREAK").contains("\"failures\":3");
	}

	// ---------------------------------------------------------------- modo da rede e painel

	/**
	 * Nó fora do balanceamento não conta vaga. Contar faria a razão fila/vagas parecer folgada e a
	 * rede não entraria em {@code HIGH_LOAD} justamente quando tem menos capacidade do que aparenta.
	 */
	@Test
	void noRemovidoNaoContaVagaNoModoDaRede() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-fora", List.of("NODE_WORKER"));
		failThreeTimes(node);

		// 4 vagas declaradas, 0 rodando: sem o filtro, cinco tarefas na fila dariam razão 1.25 e
		// HIGH_LOAD. Com o nó fora, a capacidade é zero e a razão é infinita — também HIGH_LOAD, mas
		// pelo motivo certo. O que separa os dois casos é a capacidade contada.
		for (int i = 0; i < 5; i++) {
			tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "mode-" + i, "{}",
				DataClass.PUBLIC, null));
		}
		assertThat(modes.evaluate()).isEqualTo(NetworkMode.HIGH_LOAD);
		String reason = jdbc.queryForObject("select reason from network_state where id = 'global'", String.class);
		assertThat(reason).contains("vagas=0");
	}

	@Test
	void painelMostraONoDegradadoEOMotivoDaRemocao() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-painel", List.of("NODE_WORKER"));
		failThreeTimes(node);

		mockMvc.perform(get("/admin/network/nodes").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].degraded").value(true))
			.andExpect(jsonPath("$[0].failureStreak").value(3))
			.andExpect(jsonPath("$[0].quarantineReason").value("FAILURE_STREAK"))
			.andExpect(jsonPath("$[0].quarantinedUntil").isNotEmpty());
	}


	// ---------------------------------------------------------------- limite de concorrência na rodada

	/**
	 * O limite de concorrência declarado pelo participante vale para a rodada, não só para o
	 * heartbeat.
	 *
	 * <p>Antes desta rodada, doze tarefas na fila com um único nó de quatro núcleos eram designadas
	 * <b>todas</b> para ele: cada escolha relia a lista de candidatos do último heartbeat, que ainda
	 * dizia "zero rodando", e o limite era conferido contra um número que a própria rodada já tinha
	 * invalidado. O comportamento correto é quatro — a capacidade que ele aceitou ceder.</p>
	 */
	@Test
	void rodadaNaoDesignaMaisQueALimiteDeConcorrenciaDoNo() throws Exception {
		String admin = adminToken();
		Node only = activeNode(admin, "no-unico", List.of("NODE_WORKER"));
		for (int i = 0; i < 12; i++) {
			tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "conc-" + i, "{}",
				DataClass.PUBLIC, null));
		}

		// 4 núcleos declarados, 0 em execução: no máximo quatro nesta rodada.
		assertThat(tasks.dispatchPending()).isEqualTo(4);
		assertThat(assignedCount(only.id())).isEqualTo(4);
		// O resto continua na fila, intacto: não é erro, é falta de vaga agora.
		assertThat(tasks.countByStatus(com.socialcoisa.rede.network.domain.TaskStatus.PENDING)).isEqualTo(8);
	}

	/**
	 * A rodada seguinte só entrega a vaga que o heartbeat abriu. É o que prova que o contador é do
	 * ciclo: se ele vazasse entre rodadas, o nó ficaria travado para sempre; se não existisse, a
	 * segunda rodada entregaria outras quatro com as quatro primeiras ainda em execução.
	 */
	@Test
	void segundaRodadaSoUsaAsVagasQueOHeartbeatAbriu() throws Exception {
		String admin = adminToken();
		Node only = activeNode(admin, "no-ciclo", List.of("NODE_WORKER"));
		for (int i = 0; i < 12; i++) {
			tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "ciclo-" + i, "{}",
				DataClass.PUBLIC, null));
		}
		assertThat(tasks.dispatchPending()).isEqualTo(4);

		// O nó reporta que terminou uma e está com três em execução: abre exatamente uma vaga.
		heartbeat(only, 5.0, 3);
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedCount(only.id())).isEqualTo(5);

		// O nó passa a reportar as quatro em execução: a vaga some, e a rodada não entrega nada.
		heartbeat(only, 5.0, 4);
		assertThat(tasks.dispatchPending()).isZero();
		assertThat(assignedCount(only.id())).isEqualTo(5);
	}

	/**
	 * A designação espalha em vez de empilhar: dois nós com a mesma capacidade recebem a metade cada.
	 * Sem descontar o que a rodada já entregou, o primeiro da ordenação levaria tudo — a folga
	 * calculada continuava cheia para ele.
	 */
	@Test
	void aRodadaEspalhaEntreNosDeMesmaCapacidade() throws Exception {
		String admin = adminToken();
		Node first = activeNode(admin, "no-a", List.of("NODE_WORKER"));
		Node second = activeNode(admin, "no-b", List.of("NODE_WORKER"));
		for (int i = 0; i < 8; i++) {
			tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "spread-" + i, "{}",
				DataClass.PUBLIC, null));
		}

		assertThat(tasks.dispatchPending()).isEqualTo(8);
		assertThat(assignedCount(first.id())).isEqualTo(4);
		assertThat(assignedCount(second.id())).isEqualTo(4);
	}

	// ---------------------------------------------------------------- prioridade do nó local

	/**
	 * {@code NODE_LOCAL} tem prioridade de escalonamento, não permissão extra (ADR-02): entre nós
	 * saudáveis, o da casa recebe antes — é ele que absorve o pico para poupar a máquina do
	 * participante.
	 *
	 * <p>O cenário é montado para o código ANTIGO escolher de forma determinística o nó externo: ele
	 * tem folga cheia (4/4) contra 1/4 do local, e a ordenação antiga por folga o colocava em
	 * primeiro. A prioridade nova inverte isso — sem tocar em nenhum filtro.</p>
	 */
	@Test
	void noLocalRecebeAntesDoExternoEntreSaudaveis() throws Exception {
		String admin = adminToken();
		Node local = activeNode(admin, "no-local", List.of("NODE_WORKER", "NODE_LOCAL"));
		Node external = activeNode(admin, "no-externo", List.of("NODE_WORKER"));
		heartbeat(local, 5.0, 3);

		NetworkTask first = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "local-1",
			"{}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(first.id())).isEqualTo(local.id());

		// A prioridade não passa por cima do limite: o local ficou cheio, o resto vai ao externo.
		heartbeat(local, 5.0, 4);
		NetworkTask second = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "local-2",
			"{}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(second.id())).isEqualTo(external.id());
	}

	/**
	 * Prioridade é ordenação entre saudáveis, não privilégio: o nó local degradado perde para o
	 * externo saudável, do mesmo jeito que qualquer nó degradado perde.
	 */
	@Test
	void noLocalDegradadoPerdeParaOExternoSaudavel() throws Exception {
		String admin = adminToken();
		Node slowLocal = activeNode(admin, "no-local-lento", List.of("NODE_WORKER", "NODE_LOCAL"));
		Node healthyExternal = activeNode(admin, "no-externo-saudavel", List.of("NODE_WORKER"));
		jdbc.update("update network_nodes set observed_latency_ms = ? where id = ?",
			NetworkNode.SLOW_LATENCY_MS + 30_000, slowLocal.id());

		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "local-3",
			"{}", DataClass.PUBLIC, null));
		tasks.dispatchPending();
		assertThat(assignedNode(task.id())).isEqualTo(healthyExternal.id());
	}

	// ---------------------------------------------------------------- helpers

	private record Node(String id, String key) {
	}

	private int assignedCount(String nodeId) {
		return jdbc.queryForObject("select count(*) from network_tasks where assigned_node_id = ?", Integer.class,
			nodeId);
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_balance_admin");
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

	/** Uma tarefa que o nó recebe e reporta como falha retentável. */
	private void failOnce(Node node, String key) throws Exception {
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, key, "{}",
			DataClass.PUBLIC, null));
		tasks.dispatchPending();
		assertThat(assignedNode(task.id())).isEqualTo(node.id());
		report(node, task.id(), 1, "FAILED", Map.of("code", "BOOM", "message", "falhou", "retryable", true));
	}

	private void failThreeTimes(Node node) throws Exception {
		for (int i = 0; i < NetworkNode.FAILURE_STREAK_LIMIT; i++) {
			failOnce(node, "streak-" + node.id() + "-" + i);
		}
	}

	private void completeSuccessfully(Node node, String key) throws Exception {
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, key, "{}",
			DataClass.PUBLIC, null));
		tasks.dispatchPending();
		assertThat(assignedNode(task.id())).isEqualTo(node.id());
		report(node, task.id(), 1, "DONE", null);
	}

	private void report(Node node, String taskId, int attempt, String status, Map<String, Object> error)
			throws Exception {
		Map<String, Object> body = new java.util.HashMap<>();
		body.put("taskId", taskId);
		body.put("attempt", attempt);
		body.put("status", status);
		if (error != null) {
			body.put("error", error);
		}
		mockMvc
			.perform(post("/network/tasks/" + taskId + "/result").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isOk());
	}

	private NetworkNode node(String id) {
		return jdbc.queryForObject("select * from network_nodes where id = ?", (rs, rowNum) -> new NetworkNode(
			rs.getString("id"), rs.getString("display_name"), rs.getString("owner_user_id"),
			com.socialcoisa.rede.network.domain.NodeStatus.valueOf(rs.getString("status")),
			java.util.Set.of(com.socialcoisa.rede.network.domain.NodeProfile.NODE_WORKER), rs.getString("region"),
			rs.getString("country"), rs.getString("runtime_version"),
			rs.getTimestamp("last_seen_at") == null ? null : rs.getTimestamp("last_seen_at").toInstant(),
			rs.getInt("declared_cpu_cores"), rs.getInt("declared_memory_mb"), rs.getInt("declared_disk_mb"),
			rs.getInt("declared_bandwidth_mbps"),
			rs.getObject("observed_cpu_percent") == null ? null : rs.getDouble("observed_cpu_percent"),
			rs.getObject("observed_memory_mb") == null ? null : rs.getInt("observed_memory_mb"),
			rs.getObject("observed_running_tasks") == null ? null : rs.getInt("observed_running_tasks"),
			rs.getObject("observed_latency_ms") == null ? null : rs.getInt("observed_latency_ms"),
			rs.getInt("consecutive_failures"),
			rs.getTimestamp("quarantined_until") == null ? null : rs.getTimestamp("quarantined_until").toInstant(),
			rs.getString("quarantine_reason")), id);
	}

	private int auditCount(String action, String targetId) {
		return jdbc.queryForObject("select count(*) from audit_logs where action = ? and target_id = ?",
			Integer.class, action, targetId);
	}

	private String assignedNode(String taskId) {
		return jdbc.queryForObject("select assigned_node_id from network_tasks where id = ?", String.class, taskId);
	}

	private String statusOf(String taskId) {
		return jdbc.queryForObject("select status from network_tasks where id = ?", String.class, taskId);
	}
}
