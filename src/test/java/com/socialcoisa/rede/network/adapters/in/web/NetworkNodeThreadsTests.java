package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Threads do processo do runtime no painel do nó (brief §8.2, bloco "Runtime", item "Threads").
 *
 * <p>O runtime mede o `Threads:` do próprio processo (harness `node-threads-test`); aqui se prova o
 * outro lado: o valor do heartbeat vira a amostra do minuto na coluna `threads` (V388), e o runtime
 * que não manda o campo deixa <b>nulo</b> — nunca 0, que afirmaria um processo sem thread nenhuma.
 * Zero é a linha de base de uma coluna ausente, e é justamente o que não se pode inventar.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkNodeThreadsTests {

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
		jdbc.update("delete from network_node_samples");
		jdbc.update("delete from network_nodes");
	}

	@Test
	void oThreadsDoHeartbeatViraAmostra() throws Exception {
		Node node = activeNode("threads");

		heartbeat(node, Map.of("cpuPercent", 3.0, "runningTasks", 0, "threads", 7));

		Integer threads = jdbc.queryForObject(
			"select threads from network_node_samples where node_id = ?", Integer.class, node.id());
		assertThat(threads).as("o valor do processo vai para a amostra").isEqualTo(7);
	}

	@Test
	void runtimeSemOTerCampoDeixaNuloENaoZero() throws Exception {
		Node node = activeNode("sem-threads");

		heartbeat(node, Map.of("cpuPercent", 3.0, "runningTasks", 0));

		Integer threads = jdbc.queryForObject(
			"select threads from network_node_samples where node_id = ?", Integer.class, node.id());
		assertThat(threads).as("não medido é nulo; zero afirmaria processo sem thread").isNull();
	}

	private void heartbeat(Node node, Map<String, Object> observed) throws Exception {
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id()).header("X-Node-Key", node.key())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed", observed))))
			.andExpect(status().isOk());
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_threads_admin");
		com.socialcoisa.rede.shared.web.TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	/**
	 * Nó ACTIVE pelo caminho real (provision-token → enroll → aprovação → chave → heartbeat), o mesmo
	 * dos outros testes do painel. É ele que dá a amostra no minuto corrente.
	 */
	private Node activeNode(String name) throws Exception {
		String admin = adminToken();
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "no-" + name,
					"ownerUserId", "owner-thr-" + name, "profiles", List.of("NODE_WORKER")))))
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
		return new Node(nodeId, key);
	}

	private record Node(String id, String key) {
	}
}