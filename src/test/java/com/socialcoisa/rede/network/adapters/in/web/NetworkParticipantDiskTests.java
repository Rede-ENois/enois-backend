package com.socialcoisa.rede.network.adapters.in.web;

import static org.hamcrest.Matchers.nullValue;
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

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * O participante vendo o <b>próprio disco</b> (brief §18): "consultar espaço utilizado" e, ao lado,
 * o reservado.
 *
 * <p>Antes o {@code MyNodeView} trazia o declarado e nada mais. O participante é justamente quem
 * controla a infraestrutura — e não conseguia ver quanto dela o Énóis estava ocupando. O caso do §7
 * (2 TB de volume, 500 GB guardados) só faz sentido na tela dele se os três números estiverem
 * juntos: cedido, usado e reservado.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkParticipantDiskTests {

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

	@Test
	void participante_ve_o_que_cedeu_o_que_o_enois_ocupa_e_o_que_guardou() throws Exception {
		var dono = auth.authenticate("net_dono_disco");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId(), 1_500_000);
		String key = issueKey(admin, nodeId);

		// O runtime mede: 2 TB de volume, 400 GB ocupados pelo Énóis, 500 GB livres.
		Map<String, Object> heartbeat = new HashMap<>();
		heartbeat.put("runtimeVersion", "0.1.0");
		heartbeat.put("observed", Map.of("cpuPercent", 3.0, "memoryMb", 512, "runningTasks", 0,
			"diskFreeMb", 500_000L, "diskTotalMb", 2_000_000L, "usedMb", 400_000L));
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(heartbeat)))
			.andExpect(status().isOk());

		mockMvc.perform(get("/network/participant/nodes/" + nodeId).header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			// O que ele cedeu.
			.andExpect(jsonPath("$.declaredDiskMb").value(1_500_000))
			// O que o Énóis ocupa, medido pelo PRÓPRIO nó.
			.andExpect(jsonPath("$.observedContentUsedMb").value(400_000))
			// `total - cedido`: o que ele guardou para si (2 TB - 1,5 TB).
			.andExpect(jsonPath("$.reservedDiskMb").value(500_000))
			// 400.000 / 1.500.000.
			.andExpect(jsonPath("$.diskUsedPercent").value(26.666666666666668));
	}

	/**
	 * Nó que ainda não mediu: os três campos de uso ficam <b>nulos</b>, não zero. Zero diria "não
	 * ocupa nada" e "não reservou nada" — o oposto de "não sabemos", e o participante ajustaria o
	 * próprio limite a partir de um número inventado.
	 */
	@Test
	void no_que_ainda_nao_mediu_tem_uso_nulo_nao_zero() throws Exception {
		var dono = auth.authenticate("net_dono_sem_medida");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId(), 1_500_000);

		mockMvc.perform(get("/network/participant/nodes").header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].declaredDiskMb").value(1_500_000))
			.andExpect(jsonPath("$[0].observedContentUsedMb").value(nullValue()))
			.andExpect(jsonPath("$[0].diskUsedPercent").value(nullValue()))
			.andExpect(jsonPath("$[0].reservedDiskMb").value(nullValue()));
	}

	/**
	 * Uso acima do que ele cedeu aparece como está. Cortar em 100 esconderia do participante
	 * exatamente o caso em que ele precisa agir.
	 */
	@Test
	void uso_acima_do_cedido_aparece_acima_de_cem() throws Exception {
		var dono = auth.authenticate("net_dono_estourado");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId(), 400_000);
		String key = issueKey(admin, nodeId);

		Map<String, Object> heartbeat = new HashMap<>();
		heartbeat.put("runtimeVersion", "0.1.0");
		heartbeat.put("observed", Map.of("cpuPercent", 3.0, "memoryMb", 512, "runningTasks", 0,
			"diskFreeMb", 100_000L, "diskTotalMb", 2_000_000L, "usedMb", 440_000L));
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(heartbeat)))
			.andExpect(status().isOk());

		mockMvc.perform(get("/network/participant/nodes/" + nodeId).header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.diskUsedPercent").value(110.0));
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_participant_disk_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private String storageNodeFor(String admin, String ownerUserId, int declaredMb) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "no-do-participante",
					"ownerUserId", ownerUserId, "profiles", List.of("NODE_STORAGE")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
			.get("token").asString();
		var enroll = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 4, "memoryMb", 8192, "diskMb", declaredMb, "bandwidthMbps", 100)))))
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