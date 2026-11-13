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
 * Fase 6, testes de desastre: os cenários que a doc 06 §7 exige, um por método.
 *
 * <p>Estes testes existem para o defeito que ninguém vê: a promessa está escrita e o código não a
 * cumpre, então a operação só descobre no incidente. Cada cenário aqui falha no código anterior e
 * passa no corrigido — o que ele fixa é o comportamento observável, não a forma da implementação.</p>
 *
 * <p>Coberto nesta rodada: §5, <b>revogação durante processamento</b>. Continuam abertos os demais
 * itens da lista (Control Plane fora, dois controladores, sobrecarga, recuperação).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkDisasterTests {

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

	// ---------------------------------------------------------------- revogação no meio

	/**
	 * O cenário da doc 06 §5 ao pé da letra: o nó está executando e é revogado.
	 *
	 * <p>Antes desta rodada o resultado que chegava depois morria em {@code 401 NODE_KEY_INVALID} na
	 * autenticação — a revogação do nó revoga as chaves dele na mesma transação —, e
	 * {@code acceptResult} nunca era alcançado. A consequência não é só o corpo da resposta: o
	 * descarte prometido em auditoria não era gravado, então a tarefa redistribuída ficava sem
	 * rastro de que alguém havia reportado resultado para ela. O resultado sumia em silêncio.</p>
	 *
	 * <p>Agora: a chave revogada ainda <b>prova</b> de quem veio o resultado (é hash de uma chave que
	 * este nó recebeu), o resultado é descartado com {@code NODE_REVOKED} — nunca aplicado — e o
	 * descarte entra na trilha. Descartar não é autorizar: a mesma chave revogada continua barrada em
	 * todas as outras rotas, e nada aqui devolve ao nó o direito de operar.</p>
	 */
	@Test
	void revogacaoNoMeioDaTarefaDescartaOResultadoEAudita() throws Exception {
		String admin = adminToken();
		Node revoked = activeNode(admin, "no-revogado", List.of("NODE_WORKER"));

		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "desastre-1",
			"{}", DataClass.PUBLIC, null));
		// Um nó só, de propósito: a tarefa está na mão DELE quando a revogação chega.
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(task.id())).isEqualTo(revoked.id());
		assertThat(attemptOf(task.id())).isEqualTo(1);

		// Revogação administrativa no meio da execução. A chave do nó morre junto.
		mockMvc
			.perform(post("/admin/network/nodes/" + revoked.id() + "/status").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("status", "REVOKED"))))
			.andExpect(status().isOk());
		assertThat(activeKeys(revoked.id())).isZero();

		// Outro nó entra para receber a tarefa — a redistribuição da §5 item 3.
		Node healthy = activeNode(admin, "no-herdeiro", List.of("NODE_WORKER"));
		tasks.drainSuspendedNodes();
		assertThat(statusOf(task.id())).isEqualTo("PENDING");
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(task.id())).isEqualTo(healthy.id());
		assertThat(attemptOf(task.id())).isEqualTo(2);

		// O nó revogado "acorda" e reporta o resultado da tentativa 1, com a chave que era dele.
		mockMvc
			.perform(post("/network/tasks/" + task.id() + "/result").header("X-Node-Id", revoked.id())
				.header("X-Node-Key", revoked.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("taskId", task.id(), "attempt", 1, "status", "DONE", "output", "{}"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.applied").value(false))
			.andExpect(jsonPath("$.reason").value("NODE_REVOKED"));

		// Descartado de verdade: a tarefa segue na mão de quem pode executá-la, sem resultado aplicado.
		assertThat(statusOf(task.id())).isEqualTo("DISPATCHED");
		assertThat(assignedNode(task.id())).isEqualTo(healthy.id());
		assertThat(attemptOf(task.id())).isEqualTo(2);
		assertThat(jdbc.queryForObject("select output from network_tasks where id = ?", String.class, task.id()))
			.isNull();

		// E o descarte foi registrado: nó de origem, motivo e a tentativa que o nó declarou.
		String metadata = jdbc.queryForObject(
			"select metadata from audit_logs where action = 'NETWORK_TASK_RESULT_DISCARDED' and target_id = ?",
			String.class, task.id());
		assertThat(metadata).contains("\"reason\":\"NODE_REVOKED\"")
			.contains("\"reportedAttempt\":1")
			.contains(revoked.id());
		assertThat(jdbc.queryForObject(
			"select actor_id from audit_logs where action = 'NETWORK_TASK_RESULT_DISCARDED' and target_id = ?",
			String.class, task.id())).isEqualTo(revoked.id());

		// O trilho tem uma linha só: reenviar não vira enxurrada de auditoria por engano do teste.
		assertThat(jdbc.queryForObject(
			"select count(*) from audit_logs where action = 'NETWORK_TASK_RESULT_DISCARDED' and target_id = ?",
			Integer.class, task.id())).isEqualTo(1);
	}

	/**
	 * O descarte vale só para resultado. A chave revogada continua não abrindo nenhuma outra porta —
	 * a fase 6 não pode virar um caminho de volta para o nó expulso.
	 *
	 * <p>É guarda de escopo: se alguém "resolver" a revogação aceitando a chave revogada em todo
	 * lugar, este teste cai. {@code GET /network/tasks/next} é o pior deles, porque é por onde o nó
	 * pegaria trabalho novo.</p>
	 */
	@Test
	void chaveRevogadaContinuaBarradaForaDaRotaDeResultado() throws Exception {
		String admin = adminToken();
		Node revoked = activeNode(admin, "no-expulso", List.of("NODE_WORKER"));
		revoke(admin, revoked.id());

		mockMvc
			.perform(get("/network/tasks/next").header("X-Node-Id", revoked.id())
				.header("X-Node-Key", revoked.key()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("NODE_KEY_INVALID"));

		mockMvc
			.perform(get("/network/tasks/config").header("X-Node-Id", revoked.id())
				.header("X-Node-Key", revoked.key()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("NODE_KEY_INVALID"));
	}

	/**
	 * Chave que nunca foi deste nó não vira descarte: o descarte é prova de identidade, e sem prova
	 * não há auditoria. Um terceiro que soubesse o id de um nó revogado não pode escrever linhas de
	 * auditoria com o nome dele.
	 */
	@Test
	void chaveDeOutroNoNaoProduzDescarteAuditado() throws Exception {
		String admin = adminToken();
		Node primeiro = activeNode(admin, "no-revogado-b", List.of("NODE_WORKER"));
		Node segundo = activeNode(admin, "no-intruso", List.of("NODE_WORKER"));

		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "desastre-2",
			"{}", DataClass.PUBLIC, null));
		tasks.dispatchPending();
		// Os dois nós são idênticos para o escalonamento, e o último critério da ordenação é
		// aleatório de propósito (para não concentrar carga). Fixar "o primeiro" aqui seria fixar
		// um sorteio: o teste passaria ou falharia conforme a roleta. O que o teste precisa é de UM
		// nó revogado e de UM intruso com a chave de outro — qualquer par serve.
		String designado = assignedNode(task.id());
		Node revoked = primeiro.id().equals(designado) ? primeiro : segundo;
		Node other = revoked == primeiro ? segundo : primeiro;
		revoke(admin, revoked.id());

		// A chave do outro nó, apresentada com o id do revogado: 401, e nenhuma auditoria.
		mockMvc
			.perform(post("/network/tasks/" + task.id() + "/result").header("X-Node-Id", revoked.id())
				.header("X-Node-Key", other.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("taskId", task.id(), "attempt", 1, "status", "DONE", "output", "{}"))))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("NODE_KEY_INVALID"));

		assertThat(jdbc.queryForObject(
			"select count(*) from audit_logs where action = 'NETWORK_TASK_RESULT_DISCARDED' and target_id = ?",
			Integer.class, task.id())).isZero();
	}

	// ---------------------------------------------------------------- helpers

	private record Node(String id, String key) {
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_disaster_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private void revoke(String admin, String nodeId) throws Exception {
		mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/status").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("status", "REVOKED"))))
			.andExpect(status().isOk());
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

	private int activeKeys(String nodeId) {
		return jdbc.queryForObject(
			"select count(*) from network_node_keys where node_id = ? and revoked_at is null", Integer.class,
			nodeId);
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