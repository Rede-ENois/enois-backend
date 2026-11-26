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
 * Retentativas das tarefas, por nó e na fila (brief §8.2, "retentativas").
 *
 * <p>O que estava errado tinha duas metades: `attempt` não subia na devolução à fila (uma tarefa
 * devolvida voltava com o mesmo número, e "quantas tarefas estão em retentativa" não tinha resposta),
 * e o painel do nó não dizia quantas tentativas ele precisa até concluir. O que aqui se prende:</p>
 *
 * <ul>
 * <li>o {@code attempt} conta ENTREGAS: a devolução à fila não infla o número, o claim seguinte
 * incrementa — e é isso que o {@code retried} do painel conta;</li>
 * <li>a tarefa devolvida e depois concluída entra no painel do nó — com a contagem SEPARADA da
 * duração (uma tarefa que falhou duas vezes e concluiu não estica a cauda do tempo de execução);</li>
 * <li>tarefa concluída de primeira não entra: {@code retriesInWindow} continua <b>null</b>, e não 0.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkTaskRetryTests {

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
		jdbc.update("delete from network_task_latency_buckets");
		jdbc.update("delete from network_tasks");
		jdbc.update("delete from network_state");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	@Test
	void tarefaQueVoltaAFilaSobeATentativaEConcluidaEntraNoPainelSeparadaDaDuracao() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "retry");

		// Primeira entrega: o claim já contou a entrega (attempt = 1). Ela falha de forma retentável e
		// volta à fila com backoff (o próximo instante vem do backoff do serviço, por isso a linha é
		// escrita em PENDING com `next_attempt_at` no passado).
		String taskId = enqueueAndDispatch(node);
		int primeiraEntrega = attemptOf(taskId);
		assertThat(primeiraEntrega).as("o claim conta a primeira entrega").isEqualTo(1);
		entregueNaTentativa.put(taskId, primeiraEntrega);
		report(node, taskId, "FAILED", Map.of("code", "TASK_FAILED", "retryable", true));
		assertThat(attemptOf(taskId))
			.as("devolver à fila NÃO infla a tentativa; quem conta a entrega é o claim")
			.isEqualTo(primeiraEntrega);
		assertThat(jdbc.queryForObject("select status from network_tasks where id = ?", String.class, taskId))
			.isEqualTo("PENDING");

		// A próxima entrega: o claim incrementa. Sem outra rodada de recuperação de claim no meio, o
		// caminho é determinístico — duas entregas.
		jdbc.update("update network_tasks set next_attempt_at = ? where id = ?",
			java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(5)), taskId);
		tasks.dispatchPending();
		int naEntrega = attemptOf(taskId);
		assertThat(naEntrega).as("a segunda entrega é a tentativa 2").isEqualTo(2);
		entregueNaTentativa.put(taskId, naEntrega);

		// Conclui nesta tentativa: as anteriores não concluíram.
		report(node, taskId, "DONE", null);

		// A duração entra na distribuição de execução; a tentativa, na de tentativas — desde o
		// provedor, e sem se misturarem.
		// A tentativa que falhou também tocou o desfecho (foi descartada). Ela não pode entrar na
		// duração nem virar uma segunda amostra de tentativa: `samples` soma 1 e `retries` soma 1 (uma
		// tarefa concluída que precisou de retentativa). A QUANTIDADE de tentativas perdidas vive na
		// faixa, não na contagem: é ela que o teste confere.
		var linhas = jdbc.queryForList(
			"select bucket_upper_ms, samples, retries from network_task_latency_buckets where node_id = ? "
				+ "order by bucket_upper_ms", node.id());
		int totalSamples = 0;
		int totalRetries = 0;
		for (var linha : linhas) {
			totalSamples += ((Number) linha.get("SAMPLES")).intValue();
			totalRetries += ((Number) linha.get("RETRIES")).intValue();
		}
		assertThat(totalSamples).as("só a conclusão conta na duração").isEqualTo(1);
		assertThat(totalRetries).as("uma conclusão que precisou de retentativa").isEqualTo(1);
		var faixaDaTentativa = jdbc.queryForMap(
			"select bucket_upper_ms, retries from network_task_latency_buckets where node_id = ? and retries > 0",
			node.id());
		assertThat(((Number) faixaDaTentativa.get("BUCKET_UPPER_MS")).intValue())
			.as("a faixa da contagem é a das tentativas perdidas, não a da duração")
			.isEqualTo(com.socialcoisa.rede.network.domain.TaskLatency.bucketOf(naEntrega - 1L));
		assertThat(((Number) faixaDaTentativa.get("RETRIES")).intValue()).isEqualTo(1);

		mockMvc
			.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.latency.samplesInWindow").value(1))
			.andExpect(jsonPath("$.latency.retriesInWindow").value(1))
			// A faixa da contagem de tentativas: é tentativa, não duração em segundos.
			.andExpect(jsonPath("$.latency.retriesP50").isNumber());
	}

	@Test
	void tarefaConcluidaDePrimeiraNaoTemDistribuicaoDeTentativa() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "primeira");

		String taskId = enqueueAndDispatch(node);
		report(node, taskId, "DONE", null);
		assertThat(attemptOf(taskId)).isEqualTo(1);

		mockMvc
			.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.latency.retriesInWindow").value(0))
			// Nulo, não zero: "concluiu de primeira" não é uma tentativa medida.
			.andExpect(jsonPath("$.latency.retriesP50").doesNotExist())
			.andExpect(jsonPath("$.latency.p50Ms").isNumber());
	}

	@Test
	void overviewContaAsTarefasQueVoltaramAFilaEAsQueConcluiram() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "fila");

		// Uma concluída de primeira, uma devolvida e ainda na fila, uma devolvida e concluída.
		String primeira = enqueueAndDispatch(node);
		report(node, primeira, "DONE", null);

		String pendente = enqueueAndDispatch(node);
		report(node, pendente, "FAILED", Map.of("code", "TASK_FAILED", "retryable", true));

		String concluida = enqueueAndDispatch(node);
		report(node, concluida, "FAILED", Map.of("code", "TASK_FAILED", "retryable", true));
		jdbc.update("update network_tasks set next_attempt_at = ? where id = ?",
			java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(5)), concluida);
		tasks.dispatchPending();
		report(node, concluida, "DONE", null);

		// Uma quarta, recém-criada e nunca despachada: está na fila e NÃO é retentativa. Sem ela,
		// `pending` e `retried` seriam o mesmo número nesta cena e a asserção não distinguiria nada.
		tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB,
			"ret-" + java.util.UUID.randomUUID(), "{}", DataClass.PUBLIC, null, null, node.id()));

		mockMvc
			.perform(get("/admin/network/tasks/overview").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.done").value(2))
			.andExpect(jsonPath("$.pending").value(2))
			// Só a que está aberta E já falhou uma vez: a concluída e a recém-criada ficam de fora.
			.andExpect(jsonPath("$.retried").value(1));
	}

	/**
	 * O `attempt` é o da linha NO BANCO. O objeto devolvido pelo `dispatchPending` mantém o valor lido
	 * antes do claim, e é justamente a guarda de tentativa do resultado que se está exercitando aqui.
	 * {@code entregueNaTentativa} é a tentativa declarada pela ENTREGA, e é a que o serviço confere.
	 */
	private void report(Node node, String taskId, String status, Map<String, Object> error) throws Exception {
		int attempt = entregueNaTentativa.getOrDefault(taskId,
			jdbc.queryForObject("select attempt from network_tasks where id = ?", Integer.class, taskId));
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

	/** Tentativa em que o NÓ recebeu a tarefa (o que ele devolve no resultado), por tarefa. */
	private final Map<String, Integer> entregueNaTentativa = new java.util.concurrent.ConcurrentHashMap<>();

	private int attemptOf(String taskId) {
		return jdbc.queryForObject("select attempt from network_tasks where id = ?", Integer.class, taskId);
	}

	private String enqueueAndDispatch(Node node) {
		var task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB,
			"ret-" + java.util.UUID.randomUUID(), "{}", DataClass.PUBLIC, null, null, node.id()));
		tasks.dispatchPending();
		assertThat(jdbc.queryForObject("select status from network_tasks where id = ?", String.class, task.id()))
			.as("a tarefa saiu da fila para o nó do cenário").isEqualTo("DISPATCHED");
		return task.id();
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_retry_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	/**
	 * Nó ativo com heartbeat recente, pelo caminho real (provision-token → enroll → aprovação → chave).
	 * Sem heartbeat recente `dispatchPending` não escolhe o nó e a tarefa fica PENDING.
	 */
	private Node activeNode(String admin, String name) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "no-" + name,
					"ownerUserId", "owner-ret-" + name, "profiles", List.of("NODE_WORKER")))))
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