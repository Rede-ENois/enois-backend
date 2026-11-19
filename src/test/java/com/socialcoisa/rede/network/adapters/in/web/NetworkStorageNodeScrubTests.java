package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
 * A rota pela qual o nó devolve o que mediu no próprio disco, e o Control Plane só rebaixa a cópia
 * <b>dele</b>.
 *
 * <p>Pela rota real, três coisas ficam provadas de uma vez: a autenticação de nó vale (sem chave,
 * 401 antes de qualquer leitura de relatório); um nó <b>não</b> rebaixa a cópia de outro — é o
 * ataque mais barato da rede, derrubar a durabilidade alheia com um POST; e a cópia própria
 * declarada divergente fica {@code STALE}, visível para quem decide re-replicar.</p>
 */
@SpringBootTest(properties = "rede.storage.node-body-root=target/test-node-scrub-bodies")
@AutoConfigureMockMvc
class NetworkStorageNodeScrubTests {

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
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_scrub_admin_" + System.nanoTime());
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	/** Nó completo pelo caminho real: provisionamento, aprovação, chave e heartbeat. */
	private Node node(String nome) throws Exception {
		String admin = adminToken();
		var token = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", nome, "ownerUserId", "owner-" + nome,
					"profiles", List.of("NODE_STORAGE")))))
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
		var chave = mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		String key = objectMapper.readTree(chave.getResponse().getContentAsString()).get("apiKey").asString();
		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
				Map.of("cpuPercent", 5.0, "memoryMb", 1024, "runningTasks", 0, "diskFreeMb", 51200)))))
			.andExpect(status().isOk());
		return new Node(nodeId, key);
	}

	private String objectIdNoBanco(String sha) {
		String id = "sob_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		jdbc.update("""
			insert into network_storage_objects (id, bucket, object_key, content_type, bytes, sha256, state,
			  created_by, created_at, updated_at)
			values (?, 'fotos', 'gato.jpg', 'image/jpeg', 8, ?, 'STORED', 'u', now(3), now(3))
			""", id, sha);
		return id;
	}

	private void colocacao(String objectId, String nodeId, String state) {
		jdbc.update("""
			insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
			values (?, 1, ?, 8, ?, ?, now(3))
			""", objectId, nodeId, "a".repeat(64), state);
	}

	@Test
	void no_marca_a_propria_copia_como_stale_quando_ela_nao_confere() throws Exception {
		Node no = node("nd_scrub_a");
		String id = objectIdNoBanco("a".repeat(64));
		colocacao(id, no.id(), "OK");

		var resposta = mockMvc
			.perform(post("/network/storage/scrub").header("X-Node-Id", no.id()).header("X-Node-Key", no.key())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					List.of(Map.of("objectId", id, "actualSha256", "b".repeat(64))))))
			.andExpect(status().isOk())
			.andReturn();

		assertThat(objectMapper.readTree(resposta.getResponse().getContentAsString()).get("stale").asInt()).isEqualTo(1);
		String estado = jdbc.queryForObject("select state from network_storage_placements where object_id = ?",
			String.class, id);
		assertThat(estado).isEqualTo("STALE");
	}

	@Test
	void um_no_nao_rebaixa_a_copia_de_outro() throws Exception {
		Node dono = node("nd_scrub_dono");
		Node intruso = node("nd_scrub_intruso");
		String id = objectIdNoBanco("a".repeat(64));
		colocacao(id, dono.id(), "OK");

		// O intruso declara a cópia do dono como ruim. O relatório é dele; a cópia não é.
		mockMvc.perform(post("/network/storage/scrub").header("X-Node-Id", intruso.id())
			.header("X-Node-Key", intruso.key()).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(List.of(Map.of("objectId", id, "actualSha256", "b".repeat(64))))))
			.andExpect(status().isOk());

		String estado = jdbc.queryForObject("select state from network_storage_placements where object_id = ?",
			String.class, id);
		assertThat(estado).isEqualTo("OK");
	}

	@Test
	void sem_chave_a_rota_nao_abre() throws Exception {
		mockMvc.perform(post("/network/storage/scrub").contentType(MediaType.APPLICATION_JSON).content("[]"))
			.andExpect(status().isUnauthorized());
	}

	private record Node(String id, String key) {
	}
}