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

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * O disco de <b>um</b> nó na página individual (brief §17): "espaço disponibilizado", "espaço
 * utilizado" e "espaço disponível".
 *
 * <p>O caminho HTTP inteiro é o da produção: provisionar → enroll (com o teto cedido) → heartbeat
 * (com o total e o uso medidos) → página do nó. Um teste de serviço provaria a conta; este prova
 * que os três números do participante <b>chegam</b> à tela do operador pelo caminho real.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkNodeStoragePanelTests {

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
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_storage_objects");
	}

	@Test
	void a_pagina_do_no_mostra_disponibilizado_utilizado_e_disponivel() throws Exception {
		String admin = adminToken();
		No no = storageNode(admin, "disco", 1_500_000);

		// O runtime mede: 2 TB de volume, 400 GB ocupados pelo conteúdo, 500 GB livres.
		Map<String, Object> heartbeat = new HashMap<>();
		heartbeat.put("runtimeVersion", "0.1.0");
		heartbeat.put("observed", Map.of("cpuPercent", 3.0, "memoryMb", 512, "runningTasks", 0,
			"diskFreeMb", 500_000L, "diskTotalMb", 2_000_000L, "usedMb", 400_000L));
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", no.id())
				.header("X-Node-Key", no.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(heartbeat)))
			.andExpect(status().isOk());

		mockMvc
			.perform(get("/admin/network/nodes/" + no.id() + "/storage").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nodeId").value(no.id()))
			// "Espaço disponibilizado": o teto que o participante cedeu.
			.andExpect(jsonPath("$.declaredMb").value(1_500_000))
			// "Espaço utilizado": o que o Énóis ocupa, medido no nó.
			.andExpect(jsonPath("$.usedMb").value(400_000))
			// "Espaço disponível": o livre medido no mesmo heartbeat.
			.andExpect(jsonPath("$.freeMb").value(500_000))
			// 400.000 / 1.500.000: o percentual que a página do nó mostra.
			.andExpect(jsonPath("$.usedPercent").value(26.666666666666668));
	}

	/**
	 * Nó de processamento não tem disco de armazenamento: devolve corpo vazio. Chamar de "disco" o
	 * de quem nunca guarda objeto prometeria espaço que ele não oferece.
	 */
	@Test
	void no_de_processamento_nao_tem_disco_de_armazenamento() throws Exception {
		String admin = adminToken();

		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "worker",
					"ownerUserId", "owner-worker", "profiles", List.of("NODE_WORKER")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
			.get("token").asString();
		var enroll = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 2, "memoryMb", 4096, "diskMb", 10240, "bandwidthMbps", 50)))))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(enroll.getResponse().getContentAsString()).get("nodeId").asString();

		var resposta = mockMvc
			.perform(get("/admin/network/nodes/" + nodeId + "/storage").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();

		// Mapa vazio: não há campos de disco, e não há erro -- a página do nó existe.
		assertThat(objectMapper.readTree(resposta.getResponse().getContentAsString()).size()).isZero();
	}

	@Test
	void no_desconhecido_e_404_nao_tela_vazia() throws Exception {
		String admin = adminToken();

		mockMvc.perform(get("/admin/network/nodes/nd_inexistente/storage").header("X-Session-Token", admin))
			.andExpect(status().isNotFound());
	}

	/**
	 * Sessão válida de quem <b>não</b> é admin: 403. O que este teste fixa é o fechamento da porta
	 * (o {@code AdminRouteInterceptor} exige admin em {@code /admin/**}), não o {@code admin()} da
	 * rota — tirar o {@code admin()} do controller não abre nada. É defesa em profundidade:
	 * verificado por mutação, que fica verde justamente por isso.
	 */
	@Test
	void a_pagina_do_no_exige_admin() throws Exception {
		var visitante = auth.authenticate("net_panel_visitor_storage");

		mockMvc.perform(get("/admin/network/nodes/nd_qualquer/storage").header("X-Session-Token", visitante.token()))
			.andExpect(status().isForbidden());
	}

	private record No(String id, String key) {
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_storage_panel_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	/** Nó de armazenamento pelo caminho real: token, enroll com o teto cedido, aprovação e chave. */
	private No storageNode(String admin, String name, int declaredMb) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "no-" + name,
					"ownerUserId", "owner-disk-" + name, "profiles", List.of("NODE_STORAGE")))))
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
		var key = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		String apiKey = objectMapper.readTree(key.getResponse().getContentAsString()).get("apiKey").asString();
		return new No(nodeId, apiKey);
	}

}