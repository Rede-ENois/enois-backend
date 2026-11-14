package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
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
import com.socialcoisa.rede.network.application.NodeHealthWorker;
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Fase 6, contingência §4, regra 5: nó que vê dois controladores entra em {@code QUARANTINED} e
 * para de aceitar tarefas.
 *
 * <p>As regras 1–4 (termo monotônico, quórum, config assinada com termo) garantem que dois
 * Control Planes não se promovam juntos. Elas não cobrem o outro lado: o nó que, por estar no meio
 * de uma partição, falou com os dois e aceitou o termo maior. Esse nó é a testemunha do split-brain
 * — e até esta rodada ele operava normalmente, sem que ninguém soubesse que existia um segundo
 * mando.</p>
 *
 * <p>O que estes testes fixam, cada um sendo um jeito de errar:</p>
 * <ul>
 * <li>termo maior que o da autoridade vigente é conflito: o nó sai de circulação na hora;</li>
 * <li>termo igual ou menor <b>não</b> é conflito — quem está atrasado não é culpado, e o próprio
 * nó já recusa config de termo menor (term_regressao);</li>
 * <li>o nó em conflito continua batendo coração: é por ali que ele reporta o termo, e 403 fecharia
 * o único canal de detecção;</li>
 * <li>a quarentena de termo <b>não</b> expira por relógio, ao contrário da de saúde: divergência de
 * termo não se resolve esperando;</li>
 * <li>o caminho de volta é humano (reaprovação administrativa), e ele limpa a janela;</li>
 * <li>quarentena manual (sem motivo de termo) segue respondendo 403 no heartbeat, como antes.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkTermConflictTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NetworkTaskService tasks;

	@Autowired
	private NodeHealthWorker health;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_tasks");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
		jdbc.update("delete from network_state");
		// A linha global é invariante do deploy (seed da V303) e é dela que sai o termo vigente.
		jdbc.update("insert into network_state (id, mode, reason, since, term) values ('global', 'NORMAL', ?, ?, 2)",
			"estado de teste", Timestamp.from(Instant.now()));
		jdbc.update("delete from audit_logs where action = 'NETWORK_NODE_QUARANTINED'");
	}

	// ---------------------------------------------------------------- detecção

	/**
	 * O teste que a regra 5 exige. Antes desta rodada o nó reportava termo 7 e continuava
	 * {@code ACTIVE}, recebendo tarefa: o Control Plane não tinha como saber que existia um mando
	 * maior do que o dele.
	 */
	@Test
	void noQueReportaTermoMaiorEntraEmQuarentenaEParaDeAceitar() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-conflito");

		// Q0: com o termo vigente (2), o nó está saudável e recebe trabalho — é o comportamento
		// antigo, que segue valendo para quem está em dia.
		NetworkTask before = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB,
			"antes-do-conflito", "{}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(before.id())).isEqualTo(node.id());
		report(node, before.id(), 1, "DONE", null);

		var beat = mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(heartbeatBody(7)))
			.andExpect(status().isOk())
			// 200 e não 403: o canal de detecção continua aberto; o que muda é que ele não opera.
			.andExpect(jsonPath("$.status").value("QUARANTINED"))
			.andExpect(jsonPath("$.acceptTasks").value(false))
			.andReturn();
		assertThat(beat.getResponse().getContentAsString()).contains("\"acceptTasks\":false");

		NetworkNode quarantined = node(node.id());
		assertThat(quarantined.status()).isEqualTo(NodeStatus.QUARANTINED);
		assertThat(quarantined.quarantineReason()).isEqualTo("TERM_CONFLICT");
		assertThat(quarantined.quarantined(Instant.now())).isTrue();

		assertThat(auditMetadata(node.id()))
			.contains("\"observedTerm\":7")
			.contains("\"authorityTerm\":2");

		// E não recebe mais tarefa: nem pelo despacho geral, nem perguntando na própria fila.
		NetworkTask after = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB,
			"depois-do-conflito", "{}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isZero();
		assertThat(statusOf(after.id())).isEqualTo("PENDING");
		mockMvc
			.perform(get("/network/tasks/next").header("X-Node-Id", node.id()).header("X-Node-Key", node.key()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NODE_QUARANTINED"));
	}

	/**
	 * A assimetria é o desenho, não descuido: termo menor é o passado chegando atrasado, e o nó já
	 * recusa essa config sozinho. Punir o nó por isso o tiraria de circulação a cada reconexão de um
	 * Control Plane que perdeu a promoção — e o conflito continuaria invisível, porque ninguém é
	 * culpado.
	 */
	@Test
	void termoIgualOuMenorNaoEConflito() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-em-dia");

		heartbeat(node, 2);
		assertThat(node(node.id()).status()).isEqualTo(NodeStatus.ACTIVE);
		assertThat(node(node.id()).quarantineReason()).isNull();

		heartbeat(node, 1);
		assertThat(node(node.id()).status()).isEqualTo(NodeStatus.ACTIVE);
		assertThat(node(node.id()).quarantineReason()).isNull();
		assertThat(auditCount(node.id())).isZero();

		// E continua recebendo tarefa — nada de punição por precaução.
		tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "em-dia", "{}", DataClass.PUBLIC,
			null));
		assertThat(tasks.dispatchPending()).isEqualTo(1);
	}

	/** Nó sem termo aceito (nunca recebeu config) não entra em conflito por omissão. */
	@Test
	void noSemTermoReportadoNaoEntraEmConflito() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-novo");

		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));

		assertThat(node(node.id()).status()).isEqualTo(NodeStatus.ACTIVE);
	}

	// ---------------------------------------------------------------- depois do conflito

	/**
	 * A quarentena de termo não tem prazo, ao contrário da de saúde (V297). Se o relógio a
	 * liberasse, o nó voltaria a operar ainda dividido entre dois mandos — que é exatamente o estado
	 * que a regra 5 proíbe.
	 */
	@Test
	void quarentenaDeTermoNaoExpiraPorRelogio() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-conflito-sem-prazo");
		heartbeat(node, 9);

		// Mesmo com a janela de revisão no passado, o worker de saúde não o devolve: ele só
		// enxerga nós ACTIVE, e este está QUARANTINED.
		jdbc.update("update network_nodes set quarantined_until = ? where id = ?",
			Timestamp.from(Instant.now().minusSeconds(3600)), node.id());
		assertThat(health.runOnce()).isZero();
		assertThat(node(node.id()).status()).isEqualTo(NodeStatus.QUARANTINED);

		// E continua batendo coração sem 403 — o termo ainda sobe para o Control Plane.
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(heartbeatBody(9)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("QUARANTINED"))
			.andExpect(jsonPath("$.acceptTasks").value(false));
	}

	/** O único caminho de volta é decisão humana — e ela precisa limpar também a janela, não só o status. */
	@Test
	void reaprovacaoHumanaLiberaNoDeConflito() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-liberado");
		heartbeat(node, 9);
		assertThat(node(node.id()).status()).isEqualTo(NodeStatus.QUARANTINED);

		mockMvc.perform(post("/admin/network/nodes/" + node.id() + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());

		NetworkNode released = node(node.id());
		assertThat(released.status()).isEqualTo(NodeStatus.ACTIVE);
		// Status ACTIVE com a janela viva seria "liberado no papel, fora do balanceamento na prática".
		assertThat(released.quarantined(Instant.now())).isFalse();
		assertThat(released.quarantineReason()).isNull();

		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(heartbeatBody(2)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.acceptTasks").value(true));
	}

	/**
	 * Guarda de comportamento: a exceção do heartbeat vale só para a quarentena de TERM_CONFLICT.
	 * Um nó pausado a mão pelo admin continua recebendo 403, como sempre recebeu — abrir o heartbeat
	 * para toda quarentena seria mudar uma regra que ninguém pediu para mudar.
	 */
	@Test
	void quarentenaManualContinuaRecusandoHeartbeat() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-pausado");

		mockMvc
			.perform(post("/admin/network/nodes/" + node.id() + "/status").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("status", "QUARANTINED"))))
			.andExpect(status().isOk());

		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(heartbeatBody(2)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NODE_QUARANTINED"));
	}

	// ---------------------------------------------------------------- helpers

	private record Node(String id, String key) {
	}

	private String heartbeatBody(int observedTerm) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("runtimeVersion", "0.1.0");
		body.put("observed", Map.of("cpuPercent", 5.0, "memoryMb", 1024, "runningTasks", 0));
		body.put("observedTerm", observedTerm);
		return objectMapper.writeValueAsString(body);
	}

	/** Heartbeat com termo, sem exigir sucesso: o caminho do conflito responde 200 com outro status. */
	private void heartbeat(Node node, int observedTerm) throws Exception {
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(heartbeatBody(observedTerm)))
			.andExpect(status().isOk());
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_term_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private Node activeNode(String admin, String name) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", name, "ownerUserId", "owner-" + name,
					"profiles", List.of("NODE_WORKER")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString()).get("token")
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
		heartbeat(node, 2);
		return node;
	}

	private void report(Node node, String taskId, int attempt, String status, Map<String, Object> error)
			throws Exception {
		Map<String, Object> body = new HashMap<>();
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
			NodeStatus.valueOf(rs.getString("status")),
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

	private int auditCount(String targetId) {
		return jdbc.queryForObject(
			"select count(*) from audit_logs where action = 'NETWORK_NODE_QUARANTINED' and target_id = ?",
			Integer.class, targetId);
	}

	private String auditMetadata(String targetId) {
		return jdbc.queryForObject(
			"select metadata from audit_logs where action = 'NETWORK_NODE_QUARANTINED' and target_id = ? order by id desc limit 1",
			String.class, targetId);
	}

	private String assignedNode(String taskId) {
		return jdbc.queryForObject("select assigned_node_id from network_tasks where id = ?", String.class, taskId);
	}

	private String statusOf(String taskId) {
		return jdbc.queryForObject("select status from network_tasks where id = ?", String.class, taskId);
	}
}