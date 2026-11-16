package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * O {@code DONE} falso do runtime não pode virar durabilidade.
 *
 * <p>O defeito que este teste fecha está na costura entre dois sistemas que ninguém olhava junto:
 * o {@code *) } do {@code deploy/node-runtime/node-runtime.sh} responde {@code DONE} com um eco do
 * {@code idempotencyKey} para <b>todo</b> tipo de tarefa sem executor — inclusive {@code OBJECT_PUT}.
 * O Control Plane aceitava qualquer {@code DONE}, então a tarefa de réplica terminava "com sucesso"
 * sem que nada tivesse sido gravado: a colocação ficava {@code PENDING_REMOTE}, o {@code /storage/scrub}
 * seguia apontando a lacuna e o painel mostrava a tarefa concluída. Tudo verde, nada replicado.</p>
 *
 * <p>Aqui o nó faz exatamente o que o runtime faz — posta o eco — e o teste exige que o resultado
 * seja <b>recusado</b> com {code STORAGE_REPLICA_NOT_CONFIRMED}. No código anterior a esta rodada o
 * mesmo POST devolvia {code applied=true}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkStorageResultAcceptanceTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_storage_objects");
		jdbc.update("delete from network_tasks");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	record Node(String id, String key) {
	}

	private Node node(String... profiles) throws Exception {
		String admin = adminToken();
		String name = "res-" + UUID.randomUUID().toString().substring(0, 8);
		var token = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("displayName", name, "ownerUserId", "owner-" + name, "profiles", List.of(profiles)))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(token.getResponse().getContentAsString()).get("token").asString();
		var enroll = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 2, "memoryMb", 4096, "diskMb", 102400, "bandwidthMbps", 100)))))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(enroll.getResponse().getContentAsString()).get("nodeId").asString();
		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());
		var key = mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		String apiKey = objectMapper.readTree(key.getResponse().getContentAsString()).get("apiKey").asString();
		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", apiKey)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
				Map.of("cpuPercent", 5.0, "memoryMb", 1024, "runningTasks", 0, "diskFreeMb", 51200)))))
			.andExpect(status().isOk());
		return new Node(nodeId, apiKey);
	}

	/**
	 * Objeto servível com a réplica 0 no Control Plane e uma designação {@code PENDING_REMOTE} para
	 * {@code replicaNode}: exatamente o estado que o healing produz antes de o nó gravar.
	 */
	private String designatedObject(Node replicaNode) {
		String id = "sob_res_" + UUID.randomUUID().toString().substring(0, 8);
		String sha = "a".repeat(64);
		jdbc.update("""
			insert into network_storage_objects (id, bucket, object_key, content_type, bytes, sha256, state,
			  created_by, created_at, updated_at)
			values (?, 'res', 'foto.jpg', 'image/jpeg', 10, ?, 'STORED', 'u', now(3), now(3))
			""", id, sha);
		jdbc.update("""
			insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
			values (?, 0, 'nd_control_plane', 10, ?, 'OK', now(3)),
			       (?, 1, ?, 10, ?, 'PENDING_REMOTE', now(3))
			""", id, sha, id, replicaNode.id(), sha);
		return id;
	}

	/** Tarefa OBJECT_PUT designada ao nó, como o despacho a enfileira. */
	private String assignedTask(Node node, String objectId) {
		String taskId = "ntk_" + UUID.randomUUID().toString().substring(0, 8);
		String payload = "{\"objectId\":\"" + objectId + "\",\"sha256\":\"" + "a".repeat(64)
			+ "\",\"bytes\":10,\"replica\":1}";
		jdbc.update("""
			insert into network_tasks (id, task_type, status, attempt, idempotency_key, payload, data_class,
			  region, assigned_node_id, deadline, next_attempt_at, created_at, updated_at, target_node_id)
			values (?, 'OBJECT_PUT', 'DISPATCHED', 1, ?, ?, 'PUBLIC_MEDIA', null, ?, timestampadd(HOUR, 1, now(3)),
			  now(3), now(3), now(3), ?)
			""", taskId, "storage:" + objectId + ":1", payload, node.id(), node.id());
		return taskId;
	}

	private String postResult(Node node, String taskId, String output) throws Exception {
		return mockMvc
			.perform(post("/network/tasks/" + taskId + "/result").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("taskId", taskId, "attempt", 1, "status", "DONE",
					"output", output))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_res_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	@Test
	void oEcoDoRuntimeSemExecutorNaoViraReplicaConfirmada() throws Exception {
		Node storage = node("NODE_STORAGE");
		String objectId = designatedObject(storage);
		String taskId = assignedTask(storage, objectId);

		// O que o `*)` do node-runtime.sh posta hoje: DONE com eco, nada gravado.
		var body = postResult(storage, taskId, "{\"echo\":\"storage:" + objectId + ":1\"}");

		var tree = objectMapper.readTree(body);
		assertThat(tree.get("applied").asBoolean()).as("resultado aplicado: %s", body).isFalse();
		assertThat(tree.get("reason").asString()).isEqualTo("STORAGE_REPLICA_NOT_CONFIRMED");
		// A colocação continua designada: nada foi confirmado, e é isso que o estado tem de dizer.
		String state = jdbc.queryForObject(
			"select state from network_storage_placements where object_id = ? and replica = 1", String.class,
			objectId);
		assertThat(state).isEqualTo("PENDING_REMOTE");
		String status = jdbc.queryForObject("select status from network_tasks where id = ?", String.class, taskId);
		assertThat(status).as("recusa retentável volta para a fila, não morre").isEqualTo("PENDING");
	}

	@Test
	void comCopiaConfirmadaODoneEDeatado() throws Exception {
		Node storage = node("NODE_STORAGE");
		String objectId = designatedObject(storage);
		String taskId = assignedTask(storage, objectId);
		// O nó grava e confirma pelo caminho real, como o executor vai fazer.
		mockMvc
			.perform(post("/network/storage/objects/" + objectId + "/replica").header("X-Node-Id", storage.id())
				.header("X-Node-Key", storage.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("bytes", 10, "sha256", "a".repeat(64)))))
			.andExpect(status().isOk());
		// A confirmação de réplica usa bytes medidos; a tarefa agora pode concluir.
		var body = postResult(storage, taskId, "{\"bytes\":10,\"sha256\":\"" + "a".repeat(64) + "\"}");

		assertThat(objectMapper.readTree(body).get("applied").asBoolean()).as(body).isTrue();
		assertThat(jdbc.queryForObject("select status from network_tasks where id = ?", String.class, taskId))
			.isEqualTo("DONE");
	}

	@Test
	void bytesDivergentesDoMedidoRecusamAMesmoComCopiaConfirmada() throws Exception {
		Node storage = node("NODE_STORAGE");
		String objectId = designatedObject(storage);
		String taskId = assignedTask(storage, objectId);
		mockMvc
			.perform(post("/network/storage/objects/" + objectId + "/replica").header("X-Node-Id", storage.id())
				.header("X-Node-Key", storage.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("bytes", 10, "sha256", "a".repeat(64)))))
			.andExpect(status().isOk());

		var body = postResult(storage, taskId, "{\"bytes\":999}");

		var tree = objectMapper.readTree(body);
		assertThat(tree.get("applied").asBoolean()).as(body).isFalse();
		assertThat(tree.get("reason").asString()).isEqualTo("STORAGE_REPLICA_MEASURE_MISMATCH");
		// Não é retentável: o mesmo número daria a mesma divergência.
		assertThat(jdbc.queryForObject("select status from network_tasks where id = ?", String.class, taskId))
			.isEqualTo("FAILED");
	}

	@Test
	void oDespachoEnfileiraOPutParaADesignacaoPendente() throws Exception {
		String admin = adminToken();
		Node storage = node("NODE_STORAGE");
		String objectId = designatedObject(storage);

		mockMvc.perform(post("/admin/network/nodes/storage/objects/" + objectId + "/dispatch")
			.header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
				.jsonPath("$.dispatched.length()").value(1))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
				.jsonPath("$.dispatched[0].nodeId").value(storage.id()));

		var task = jdbc.queryForMap(
			"select task_type, target_node_id, assigned_node_id from network_tasks where idempotency_key = ?",
			"storage:" + objectId + ":1");
		assertThat(task.get("task_type")).isEqualTo("OBJECT_PUT");
		// Alvo = o nó da designação: a réplica não pode rodar em nó que não é o dono da colocação.
		assertThat(task.get("target_node_id")).isEqualTo(storage.id());
		assertThat(task.get("assigned_node_id")).as("tarefa designada nesta mesma chamada: %s", task)
			.isEqualTo(storage.id());
		var candidatos = jdbc.queryForList("select id, status, last_seen_at, observed_running_tasks, declared_cpu_cores from network_nodes");
		System.out.println("DEBUG candidatos=" + candidatos);
		var modes = jdbc.queryForList("select * from network_state");
		System.out.println("DEBUG modos=" + modes);
	}
}