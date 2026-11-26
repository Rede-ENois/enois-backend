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
 * Swap em uso e pressão de memória no painel do nó (brief §8.2, "Sistema operacional": "Swap",
 * "Pressão de memória").
 *
 * <p>O runtime MEDE os dois desde o ciclo 157 e os manda no corpo do heartbeat; o contrato do
 * Control Plane nunca os recebeu, e eles eram descartados na borda. O que estes testes fixam: o
 * valor do corpo vira a amostra (V389) e chega ao resumo do painel; runtime que não mede deixa
 * <b>nulo</b>, nunca 0; e a medida não é perdida quando o heartbeat seguinte não a traz — o resumo
 * mostra o último valor <b>medido</b> da janela, porque "o ponto mais novo não tem swap" e "a
 * máquina não tem swap" são afirmações diferentes.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkNodeMemoryPressureTests {

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
		jdbc.update("delete from network_content_snapshots");
		jdbc.update("delete from network_content_versions");
	}

	@Test
	void swapEPressaoDoHeartbeatViramAmostraEAparecemNoResumo() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "swap");

		heartbeat(node, Map.of("cpuPercent", 3.0, "runningTasks", 0, "swapUsedMb", 512L,
			"memoryPressurePercent", 4.5));

		Map<String, Object> linha = jdbc.queryForMap(
			"select swap_used_mb, memory_pressure_percent from network_node_samples where node_id = ?",
			node.id());
		assertThat(((Number) linha.get("swap_used_mb")).longValue()).as("swap em uso do corpo").isEqualTo(512L);
		assertThat(((Number) linha.get("memory_pressure_percent")).doubleValue()).as("pressão do PSI")
			.isEqualTo(4.5);

		mockMvc.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summary.swapUsedMbLatest").value(512))
			.andExpect(jsonPath("$.summary.memoryPressurePercentLatest").value(4.5));
	}

	@Test
	void runtimeQueNaoMedeDeixaNuloENaoZero() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "sem-swap");

		heartbeat(node, Map.of("cpuPercent", 3.0, "runningTasks", 0));

		Map<String, Object> linha = jdbc.queryForMap(
			"select swap_used_mb, memory_pressure_percent from network_node_samples where node_id = ?",
			node.id());
		// Zero afirmaria "nenhum swap em uso" e "ninguém esperou memória" para quem não leu o
		// arquivo. Nulo é "não medido".
		assertThat(linha.get("swap_used_mb")).as("swap não medido é nulo").isNull();
		assertThat(linha.get("memory_pressure_percent")).as("pressão não medida é nula").isNull();

		mockMvc.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summary.swapUsedMbLatest").doesNotExist())
			.andExpect(jsonPath("$.summary.memoryPressurePercentLatest").doesNotExist());
	}

	@Test
	void oPontoNovoSemAMedidaNaoApagaOUltimoValorMedido() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "lacuna");

		// Dois heartbeats no MESMO minuto trocam o ponto, então aqui a lacuna é simulada no banco do
		// jeito que ela acontece na série: um ponto anterior com a medida e o corrente sem ela (PSI
		// desligado no boot, swap removido em quente). É o estado normal de um nó real.
		heartbeat(node, Map.of("cpuPercent", 3.0, "runningTasks", 0, "swapUsedMb", 300L,
			"memoryPressurePercent", 2.0));
		jdbc.update("insert into network_node_samples (node_id, sampled_at, cpu_percent, running_tasks) "
			+ "values (?, dateadd('MINUTE', 1, current_timestamp), 4.0, 0)", node.id());

		mockMvc.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			// O resumo fala da janela, não do último tique: pegar a amostra mais nova mostraria "não
			// medido" para uma máquina que mediu 300 MB há um minuto.
			.andExpect(jsonPath("$.summary.swapUsedMbLatest").value(300))
			.andExpect(jsonPath("$.summary.memoryPressurePercentLatest").value(2.0))
			// E a série continua mostrando a lacuna no ponto novo: trocar o resumo não inventa
			// medida onde não houve. (A série vem do mais antigo para o mais novo — o último é o
			// ponto sem medida; o primeiro é o que a mediu.)
			.andExpect(jsonPath("$.series[-1].swapUsedMb").doesNotExist())
			.andExpect(jsonPath("$.series[-1].memoryPressurePercent").doesNotExist())
			.andExpect(jsonPath("$.series[0].swapUsedMb").value(300));
	}

	private void heartbeat(Node node, Map<String, Object> observed) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("runtimeVersion", "0.1.0");
		body.put("observed", observed);
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id()).header("X-Node-Key", node.key())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isOk());
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_swap_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private Node activeNode(String admin, String name) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "no-" + name,
					"ownerUserId", "owner-swap-" + name, "profiles", List.of("NODE_WORKER")))))
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
		return new Node(nodeId, key);
	}

	private record Node(String id, String key) {
	}
}