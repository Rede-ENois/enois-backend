package com.socialcoisa.rede.network.adapters.in.web;

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

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * O participante ajustando o que cede (brief §18: "definir capacidade máxima", "definir limites de
 * banda").
 *
 * <p>Até aqui só o admin mudava capacidade. O participante — justamente quem controla a própria
 * infraestrutura — precisava de um terceiro para emprestar mais ou menos da máquina dele.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkParticipantCapacityTests {

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
		jdbc.update("delete from network_node_logs");
		jdbc.update("delete from network_node_samples");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	/** Sobe o disco cedido e a banda; o que ele não mandou continua como estava. */
	@Test
	void participante_ajusta_disco_e_banda_e_o_resto_fica() throws Exception {
		var dono = auth.authenticate("net_dono_capacity");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());

		mockMvc
			.perform(post("/network/participant/nodes/" + nodeId + "/capacity")
				.header("X-Session-Token", dono.token()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("diskMb", 409_600, "bandwidthMbps", 250))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.declaredDiskMb").value(409_600))
			.andExpect(jsonPath("$.declaredBandwidthMbps").value(250))
			// Campo ausente mantém: cpuCores=4 e memoryMb=8192 do enroll.
			.andExpect(jsonPath("$.declaredCpuCores").value(4))
			.andExpect(jsonPath("$.declaredMemoryMb").value(8192));
	}

	/**
	 * A mudança chega ao nó pela config assinada — não há caminho paralelo. Se o participante
	 * mudasse o valor no banco sem compor a versão, o runtime continuaria aplicando o limite antigo
	 * e a tela dele diria um número que a máquina não usa.
	 */
	@Test
	void mudanca_compila_a_config_e_o_no_ve_o_limite_novo() throws Exception {
		var dono = auth.authenticate("net_dono_config");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());
		String key = issueKey(admin, nodeId);

		int antes = objectMapper
			.readTree(mockMvc
				.perform(get("/network/nodes/me/config").header("X-Node-Id", nodeId).header("X-Node-Key", key))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString())
			.get("configVersion").asInt();

		mockMvc
			.perform(post("/network/participant/nodes/" + nodeId + "/capacity")
				.header("X-Session-Token", dono.token()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("bandwidthMbps", 77))))
			.andExpect(status().isOk());

		var config = objectMapper.readTree(mockMvc
			.perform(get("/network/nodes/me/config").header("X-Node-Id", nodeId).header("X-Node-Key", key))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		// A config é versionada: o runtime só aplica o que chega com versão nova.
		org.assertj.core.api.Assertions.assertThat(config.get("configVersion").asInt()).isGreaterThan(antes);
		org.assertj.core.api.Assertions.assertThat(config.toString()).contains("77");
	}

	/** Capacidade negativa é recusada — um limite negativo não existe. */
	@Test
	void capacidade_negativa_e_recusada() throws Exception {
		var dono = auth.authenticate("net_dono_negativo");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());

		mockMvc
			.perform(post("/network/participant/nodes/" + nodeId + "/capacity")
				.header("X-Session-Token", dono.token()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("diskMb", -1))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NODE_CAPACITY_INVALID"));
	}

	/**
	 * Perfil <b>não</b> muda por esta rota. Perfil é permissão de tipo de tarefa; se o participante
	 * pudesse se dar o perfil de storage, a aprovação humana do perfil deixaria de existir. O corpo
	 * com um campo a mais é ignorado — e o perfil continua o do enroll.
	 */
	@Test
	void perfil_nao_muda_pela_rota_do_participante() throws Exception {
		var dono = auth.authenticate("net_dono_perfil");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());

		mockMvc
			.perform(post("/network/participant/nodes/" + nodeId + "/capacity")
				.header("X-Session-Token", dono.token()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("diskMb", 1024, "profiles", List.of("NODE_EDGE", "NODE_STORAGE")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.profiles.length()").value(1))
			.andExpect(jsonPath("$.profiles[0]").value("NODE_STORAGE"))
			.andExpect(jsonPath("$.declaredDiskMb").value(1024));
	}

	/** Nó de outro participante é 404 — 403 confirmaria que o id existe. */
	@Test
	void nao_ajusta_no_de_outro_participante() throws Exception {
		var dono = auth.authenticate("net_dono_capacity_alheio");
		var outro = auth.authenticate("net_outro_capacity");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, outro.userId());

		mockMvc
			.perform(post("/network/participant/nodes/" + nodeId + "/capacity")
				.header("X-Session-Token", dono.token()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("diskMb", 1))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NODE_UNKNOWN"));

		// E o nó do outro não mudou.
		mockMvc.perform(get("/network/participant/nodes/" + nodeId).header("X-Session-Token", outro.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.declaredDiskMb").value(102400));
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_capacity_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private String storageNodeFor(String admin, String ownerUserId) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "no-da-capacidade",
					"ownerUserId", ownerUserId, "profiles", List.of("NODE_STORAGE")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
			.get("token").asString();
		var enroll = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 4, "memoryMb", 8192, "diskMb", 102400, "bandwidthMbps", 100)))))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(enroll.getResponse().getContentAsString()).get("nodeId").asString();
		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());
		return nodeId;
	}

	private String issueKey(String admin, String nodeId) throws Exception {
		var result = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("apiKey").asString();
	}

}