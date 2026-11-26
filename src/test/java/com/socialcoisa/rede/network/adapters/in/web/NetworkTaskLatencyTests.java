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
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * A distribuição do tempo de execução das tarefas chega ao quadro do nó (brief §8.2, "latência p50,
 * p95 e p99").
 *
 * <p>Antes havia só `observedLatencyMs`, uma média móvel — que responde "em média" e esconde a
 * cauda. Este caminho é o novo: a tarefa concluída entra num histograma por faixa, e o quadro lê os
 * percentis da janela.</p>
 *
 * <p>O tempo de execução é o intervalo entre o despacho e o resultado, e por isso a duração de cada
 * tarefa aqui é <b>forjada</b> recuando `dispatched_at` no banco: o teste não dorme para simular
 * segundos de execução.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkTaskLatencyTests {

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
		jdbc.update("delete from network_node_logs");
		jdbc.update("delete from network_node_samples");
		jdbc.update("delete from network_task_latency_buckets");
		// O modo da rede é global e a base H2 é compartilhada: `EMERGENCY` (deixado por outro cenário)
		// faz `dispatchPending` devolver 0, e a tarefa deste teste ficaria PENDING para sempre. O
		// cenário precisa de NORMAL explicitamente, não do que a classe anterior deixou.
		jdbc.update("delete from network_state");
		jdbc.update("delete from network_tasks");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
		jdbc.update("delete from network_content_snapshots");
		jdbc.update("delete from network_content_versions");
	}

	@Test
	void tarefasConcluidasViramP50P95EP99NoQuadroDoNo() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "latencia");

		// Nove tarefas rápidas (≈300 ms) e uma lenta (≈3 s): o p50 fica nas rápidas e o p95 já está
		// sobre a lenta. É a diferença que uma média móvel não mostraria. As 300 ms ficam no meio da
		// faixa de 512 ms, com margem para o tempo real do request não empurrá-las para a faixa
		// seguinte — a duração é forjada recuando `dispatched_at`, mas o "agora" é o do serviço.
		for (int i = 0; i < 9; i++) {
			complete(node, 300);
		}
		complete(node, 3000);

		mockMvc
			.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.latency.samplesInWindow").value(10))
			// p50 = posição 5 de 10, na faixa de 512 ms: piso 0, 5/9 dela → 284.
			.andExpect(jsonPath("$.latency.p50Ms").value(284))
			// p95 = posição 10, a única lenta: entre 512 e 4096, no fim da faixa.
			.andExpect(jsonPath("$.latency.p95Ms").value(4096))
			.andExpect(jsonPath("$.latency.p99Ms").value(4096));
	}

	@Test
	void noSemTarefaConcluidaNaoTemPercentil() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "sem-tarefa");

		var panel = mockMvc
			.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.latency.samplesInWindow").value(0))
			.andReturn();
		var latency = objectMapper.readTree(panel.getResponse().getContentAsString()).get("latency");
		assertThat(latency.get("p50Ms").isNull()).as("sem tarefa não há percentil, e 0 seria medida").isTrue();
		assertThat(latency.get("p95Ms").isNull()).isTrue();
		assertThat(latency.get("p99Ms").isNull()).isTrue();
	}

	/**
	 * Falha não entra: uma tarefa que falhou não tem tempo de execução, tem tempo até a falha. Se
	 * entrasse, um nó que só falha apareceria com latência "excelente".
	 */
	@Test
	void tarefaQueFalhouNaoEntraNoHistograma() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "falha");

		String taskId = enqueueAndDispatch(node, 500);
		jdbc.update("update network_tasks set dispatched_at = ? where id = ?",
			java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)), taskId);
		report(node, taskId, "FAILED", Map.of("code", "TASK_FAILED", "retryable", false));

		Integer samples = jdbc.queryForObject(
			"select coalesce(sum(samples), 0) from network_task_latency_buckets where node_id = ?", Integer.class,
			node.id());
		assertThat(samples).as("falha não é execução concluída").isZero();
	}

	/** O histograma é por nó: a tarefa de um não pode puxar o percentil do outro. */
	@Test
	void latenciaDeUmNoNaoVazaParaOutro() throws Exception {
		String admin = adminToken();
		Node rapido = activeNode(admin, "rapido");
		Node lento = activeNode(admin, "lento");

		for (int i = 0; i < 4; i++) {
			complete(rapido, 300);
		}
		for (int i = 0; i < 4; i++) {
			complete(lento, 3000);
		}

		mockMvc
			.perform(get("/admin/network/nodes/" + rapido.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.latency.samplesInWindow").value(4))
			// Faixa de 512 ms, sem nada abaixo: o teto é a resposta — 300 ms está dentro dela.
			.andExpect(jsonPath("$.latency.p95Ms").value(512));
		mockMvc
			.perform(get("/admin/network/nodes/" + lento.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.latency.samplesInWindow").value(4))
			.andExpect(jsonPath("$.latency.p95Ms").value(4096));
	}

	/**
	 * A janela é de 24 horas: tarefa de ontem não pode puxar o percentil de hoje. Sem esta prova,
	 * a consulta poderia somar o histórico inteiro e o p99 carregaria um incidente de semanas atrás
	 * para sempre — e a asserção de janela seria decorativa.
	 */
	@Test
	void tarefaForaDaJanelaNaoEntraNoPercentil() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "janela");

		complete(node, 300);
		// Uma tarefa de 30 horas atrás, muito lenta: fora da janela de 24 h do painel.
		jdbc.update("insert into network_task_latency_buckets (node_id, metrics_hour, bucket_upper_ms, samples) "
			+ "values (?, ?, ?, ?)", node.id(),
			java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(30 * 3600).truncatedTo(
				java.time.temporal.ChronoUnit.HOURS)),
			65536, 5);

		mockMvc
			.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.latency.samplesInWindow").value(1))
			.andExpect(jsonPath("$.latency.p95Ms").value(512));
	}

	/**
	 * Duas tarefas na mesma hora caem na MESMA linha: o truncamento em hora é o que faz a escrita ser
	 * um contador (`samples + 1`) e não uma linha por tarefa. Sem esta prova, uma coluna de instante
	 * cru passaria (o banco arredonda o timestamp) e a tabela cresceria com o volume.
	 */
	@Test
	void duasTarefasNaMesmaHoraSomamNaMesmaLinha() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "soma");

		complete(node, 300);
		complete(node, 300);

		List<Map<String, Object>> rows = jdbc.queryForList(
			"select bucket_upper_ms, samples from network_task_latency_buckets where node_id = ?", node.id());
		assertThat(rows).as("uma linha só para a hora: a escrita é contador").hasSize(1);
		assertThat(((Number) rows.get(0).get("samples")).intValue()).isEqualTo(2);
	}

	/** Enfileira, coloca no nó e conclui com um tempo de execução forjado em milissegundos. */
	private void complete(Node node, long durationMs) throws Exception {
		String taskId = enqueueAndDispatch(node, durationMs);
		report(node, taskId, "DONE", null);
	}

	private String enqueueAndDispatch(Node node, long durationMs) {
		// O alvo é explícito: com dois nós no mesmo teste, deixar o balanceamento escolher ora daria
		// a tarefa ao nó errado (403 no resultado) e o teste mediria a sorte, não a latência.
		var task = tasks.enqueue(
			new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "lat-" + java.util.UUID.randomUUID(), "{}",
				DataClass.PUBLIC, null, null, node.id()));
		tasks.dispatchPending();
		// A tarefa TEM de ter saído da fila: resultado de tarefa PENDING é descartado
		// (`TASK_ATTEMPT_STALE`), e o teste mediria zero amostras sem dizer por quê.
		var despacho = jdbc.queryForMap(
			"select status, assigned_node_id from network_tasks where id = ?", task.id());
		assertThat(despacho.get("STATUS")).as("a tarefa saiu da fila para o nó do cenário")
			.isEqualTo("DISPATCHED");
		assertThat(despacho.get("ASSIGNED_NODE_ID")).isEqualTo(node.id());
		// O resultado é reportado agora; para o tempo de execução ser o desejado, o DESPACHO recua.
		jdbc.update("update network_tasks set dispatched_at = ? where id = ?",
			java.sql.Timestamp.from(java.time.Instant.now().minusMillis(durationMs)), task.id());
		return task.id();
	}

	/**
	 * Reporta o resultado com o `attempt` que a linha tem AGORA no banco.
	 *
	 * <p>O `attempt` em memória do objeto enfileirado pode ter ficado velho se outro contexto mexeu
	 * na linha (a base H2 é compartilhada). O serviço descarta o resultado com `TASK_ATTEMPT_STALE` e
	 * responde 200 — o teste passaria a medir "nada aconteceu", não a latência. Quem quiser provar
	 * que o resultado foi <b>aplicado</b> tem de usar o número que o banco guarda.</p>
	 */
	private void report(Node node, String taskId, String status, Map<String, Object> error) throws Exception {
		int attempt = jdbc.queryForObject("select attempt from network_tasks where id = ?", Integer.class, taskId);
		Map<String, Object> body = new HashMap<>();
		body.put("taskId", taskId);
		body.put("attempt", attempt);
		body.put("status", status);
		if (error != null) {
			body.put("error", error);
		}
		var resposta = mockMvc
			.perform(post("/network/tasks/" + taskId + "/result").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		// Resultado descartado não é conclusão: o cenário que mede latência não pode seguir assim.
		if (!"FAILED".equals(status)) {
			assertThat(resposta).as("o resultado de %s foi aplicado", taskId).doesNotContain("TASK_ATTEMPT_STALE");
		}
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_lat_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private Node activeNode(String admin, String name) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "no-" + name,
					"ownerUserId", "owner-lat-" + name, "profiles", List.of("NODE_WORKER")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
			.get("token").asString();

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
		// Sem heartbeat recente o nó não é candidato a receber tarefa (`findCandidates` filtra por
		// `last_seen_at`): a tarefa ficaria PENDING para sempre e o histograma nunca teria o que medir.
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
					Map.of("cpuPercent", 5.0, "memoryMb", 1024, "runningTasks", 0)))))
			.andExpect(status().isOk());
		return node;
	}

	private record Node(String id, String key) {
	}
}