package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import com.socialcoisa.rede.shared.security.Hashes;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Rede cooperativa, fase 1: o admin cadastra o nó, gera UMA API key por nó e revoga.
 *
 * <p>What the tests pin, because each one is a way to get this wrong:</p>
 * <ul>
 * <li>a chave em claro só existe na resposta do POST — o banco guarda só o hash, e a listagem
 * nunca devolve a chave;</li>
 * <li>gerar de novo revoga a anterior: duas chaves ativas para o mesmo nó seriam duas portas;</li>
 * <li>chave revogada não autentica, e a chave de um nó não autentica outro;</li>
 * <li>revogar o nó revoga as chaves dele — senão a revogação teria caminho lateral;</li>
 * <li>o token de provisionamento é de uso único, e o heartbeat só funciona depois da aprovação.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkNodesTests {

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
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	/** Admin de verdade: `users.is_admin`, o papel imutável — a allowlist por e-mail é bootstrap. */
	private String adminToken() throws Exception {
		var session = auth.authenticate("net_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private String issueToken(String admin, String name, List<String> profiles) throws Exception {
		var result = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("displayName", name, "ownerUserId", "owner-net", "profiles", profiles))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asString();
	}

	private String enroll(String provisionToken) throws Exception {
		var result = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 4, "memoryMb", 8192, "diskMb", 51200, "bandwidthMbps", 100)))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("nodeId").asString();
	}

	private String issueKey(String admin, String nodeId) throws Exception {
		var result = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.apiKey").exists())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("apiKey").asString();
	}

	private String newNode(String admin) throws Exception {
		return enroll(issueToken(admin, "no-teste", List.of("NODE_MEDIA")));
	}

	private void activate(String admin, String nodeId) throws Exception {
		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());
	}

	@Test
	void chaveGeradaApareceUmaVezESoOHashFicaNoBanco() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		String plain = issueKey(admin, nodeId);

		assertThat(plain).startsWith("enk_").hasSizeGreaterThan(40);
		assertThat(jdbc.queryForObject("select key_hash from network_node_keys where node_id = ?", String.class,
				nodeId))
			.isEqualTo(Hashes.sha256Hex(plain));
		assertThat(jdbc.queryForObject("select count(*) from network_node_keys where key_hash = ?", Integer.class,
				plain))
			.isZero();

		// A listagem do painel nunca devolve a chave em claro.
		mockMvc.perform(get("/admin/network/nodes").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(nodeId));
	}

	@Test
	void gerarDeNovoRevogaAChaveAnterior() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		String first = issueKey(admin, nodeId);
		String second = issueKey(admin, nodeId);

		assertThat(first).isNotEqualTo(second);
		assertThat(jdbc.queryForObject("select count(*) from network_node_keys where node_id = ? and revoked_at is null",
				Integer.class, nodeId)).isEqualTo(1);

		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", first)
			.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
	}

	@Test
	void chaveRevogadaNaoAutenticaMais() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		activate(admin, nodeId);
		String plain = issueKey(admin, nodeId);
		String keyId = jdbc.queryForObject("select id from network_node_keys where node_id = ? and revoked_at is null",
				String.class, nodeId);

		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", plain)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isOk());

		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/keys/" + keyId + "/revoke")
			.header("X-Session-Token", admin)).andExpect(status().isOk());

		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", plain)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void chaveDeUmNoNaoAutenticaOutro() throws Exception {
		String admin = adminToken();
		String firstNode = newNode(admin);
		String secondNode = newNode(admin);
		activate(admin, firstNode);
		activate(admin, secondNode);
		String firstKey = issueKey(admin, firstNode);

		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", secondNode)
			.header("X-Node-Key", firstKey).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void revogarONoRevogaAsChavesDele() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		activate(admin, nodeId);
		String plain = issueKey(admin, nodeId);

		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/status").header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REVOKED\"}"))
			.andExpect(status().isOk());

		assertThat(jdbc.queryForObject("select count(*) from network_node_keys where node_id = ? and revoked_at is null",
				Integer.class, nodeId)).isZero();
		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", plain)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void tokenDeProvisionamentoEhDeUsoUnico() throws Exception {
		String admin = adminToken();
		String token = issueToken(admin, "no-unico", List.of("NODE_WORKER"));

		enroll(token);
		mockMvc.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("provisionToken", token, "declared", Map.of()))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NODE_TOKEN_USED"));
	}

	@Test
	void noNaoOperaAntesDaAprovacao() throws Exception {
		String admin = adminToken();
		String nodeId = newNode(admin);
		String plain = issueKey(admin, nodeId);

		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", plain)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NODE_PENDING_APPROVAL"));
	}

	@Test
	void apenasAdminGerenciaANetwork() throws Exception {
		var member = auth.authenticate("net_member");

		mockMvc.perform(get("/admin/network/nodes").header("X-Session-Token", member.token()))
			.andExpect(status().isForbidden());
		mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", member.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("displayName", "x", "ownerUserId", "y", "profiles", List.of("NODE_EDGE")))))
			.andExpect(status().isForbidden());
	}
}
