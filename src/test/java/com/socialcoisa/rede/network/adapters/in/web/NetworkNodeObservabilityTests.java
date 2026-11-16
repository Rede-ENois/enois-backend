package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
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
 * O painel de observabilidade <b>por nó</b>: os quadros do topo do painel de monitoração, dentro da
 * página de cada nó.
 *
 * <p>O caminho é o que a arquitetura permite: o runtime do nó não abre porta (brief §17 — o nó
 * nunca é endereço de entrada), então ele <b>empurra</b> a amostra e as linhas no heartbeat que já
 * faz, e a rota administrativa devolve a janela curta. O que os testes fixam:</p>
 *
 * <ul>
 * <li>o heartbeat grava a amostra e a linha, e o painel as mostra;</li>
 * <li>dois heartbeats no mesmo minuto não abrem dois pontos (é série, não contador);</li>
 * <li>o teto é por nó e vale na escrita: 200 linhas, não "as que couberem";</li>
 * <li>a série de um nó não vaza para outro;</li>
 * <li>painel de nó existe só para admin; nó desconhecido é 404, não quadro vazio.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkNodeObservabilityTests {

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
	void heartbeatAlimentaOSerieEOConsoleDoPainelDoNo() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "painel");

		// O que o runtime manda: a amostra do ciclo e as linhas que escreveria no log dele.
		Map<String, Object> body = new HashMap<>();
		body.put("runtimeVersion", "0.1.0");
		body.put("observed", Map.of("cpuPercent", 12.5, "memoryMb", 2048, "runningTasks", 1, "diskFreeMb", 9000L));
		body.put("logs", List.of(Map.of("level", "INFO", "message", "tarefa tk_1 concluida",
			"at", "2026-11-12T12:00:00Z")));
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isOk());

		var panel = mockMvc
			.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nodeId").value(node.id()))
			.andExpect(jsonPath("$.summary.cpuAvgPercent").value(12.5))
			.andExpect(jsonPath("$.summary.memoryMbLatest").value(2048))
			.andExpect(jsonPath("$.summary.sampleCount").value(1))
			.andExpect(jsonPath("$.summary.diskFreeMbLatest").value(9000))
			.andReturn();

		var tree = objectMapper.readTree(panel.getResponse().getContentAsString());
		assertThat(tree.get("series")).hasSize(1);
		assertThat(tree.get("console")).hasSize(1);
		var line = tree.get("console").get(0);
		assertThat(line.get("level").asString()).isEqualTo("INFO");
		assertThat(line.get("message").asString()).isEqualTo("tarefa tk_1 concluida");
		// O instante é o do NÓ, não o da chegada: a linha do console aponta para quando aconteceu.
		assertThat(line.get("at").asString()).isEqualTo("2026-11-12T12:00:00Z");
		assertThat(tree.get("consoleLevels").get("INFO").asInt()).isEqualTo(1);

		// A linha é do log do painel, não da auditoria: sem tabela de auditoria envolvida.
		assertThat(jdbc.queryForObject("select count(*) from network_node_logs where node_id = ?", Integer.class,
				node.id())).isEqualTo(1);
	}

	@Test
	void doisHeartbeatsNoMesmoMinutoTrocamOPontoEmVezDeAbrirDois() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "minuto");
		heartbeat(node, 10.0, List.of());
		heartbeat(node, 40.0, List.of());

		var panel = mockMvc
			.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var tree = objectMapper.readTree(panel.getResponse().getContentAsString());
		// Um ponto só — e é o mais NOVO que vale: o gráfico mostra a medida vigente, não a primeira
		// do minuto.
		assertThat(tree.get("series")).hasSize(1);
		assertThat(tree.get("summary").get("cpuAvgPercent").asDouble()).isEqualTo(40.0);
	}

	@Test
	void oTetoDoConsoleValeNaEscritaEuMaNoNaoEmpurraOLixoParaOutro() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "teto");
		List<Map<String, String>> lines = new ArrayList<>();
		for (int i = 0; i < 250; i++) {
			// O `at` cresce: a linha 249 é a mais nova e tem de ser a que o console mostra no topo.
			lines.add(Map.of("level", "INFO", "message", "linha " + i,
				"at", "2026-11-12T12:" + String.format("%02d", i / 60) + ":" + String.format("%02d", i % 60) + "Z"));
		}
		heartbeat(node, 5.0, lines);

		var panel = mockMvc
			.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var tree = objectMapper.readTree(panel.getResponse().getContentAsString());
		assertThat(tree.get("console")).hasSize(200);
		assertThat(tree.get("console").get(0).get("message").asString()).isEqualTo("linha 249");
		// A linha mais antiga do lote caiu por teto — 250 não cabem numa janela de 200.
		assertThat(jdbc.queryForObject("select count(*) from network_node_logs where node_id = ?", Integer.class,
				node.id())).isEqualTo(200);
		assertThat(jdbc.queryForObject("select count(*) from network_node_logs where message = 'linha 0'",
				Integer.class)).isZero();
	}

	@Test
	void oPainelDeUmNoNaoMostraDadoDeOutro() throws Exception {
		String admin = adminToken();
		Node primeiro = activeNode(admin, "a");
		Node segundo = activeNode(admin, "b");
		heartbeat(primeiro, 11.0, List.of(Map.of("level", "WARN", "message", "disco apertado",
			"at", "2026-11-12T12:00:00Z")));
		heartbeat(segundo, 90.0, List.of(Map.of("level", "ERROR", "message", "falha de rede",
			"at", "2026-11-12T12:00:01Z")));

		var panel = mockMvc
			.perform(get("/admin/network/nodes/" + primeiro.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		String body = panel.getResponse().getContentAsString();
		assertThat(body).contains("disco apertado").doesNotContain("falha de rede");
		assertThat(objectMapper.readTree(body).get("summary").get("cpuAvgPercent").asDouble()).isEqualTo(11.0);
	}

	@Test
	void a_rota_diz_a_idade_da_serie_para_o_painel_nao_afirmar_sobre_dado_velho() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "idade");
		heartbeat(node, 7.0, List.of());

		var panel = mockMvc
			.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.freshness.state").value("FRESH"))
			.andReturn();
		var freshness = objectMapper.readTree(panel.getResponse().getContentAsString()).get("freshness");
		// O ponto acabou de ser empurrado, e o instante dele vai no quadro: é o que permite à tela
		// mostrar o número com a idade em vez de fingir que ele é do agora.
		assertThat(freshness.get("lastSampleAt").isNull()).isFalse();
		assertThat(freshness.get("ageSeconds").asLong()).isLessThan(180);
	}

	@Test
	void no_sem_amostra_diz_NO_SAMPLES_em_vez_de_metricas_zeradas() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "sem-amostra");

		// Sem heartbeat nenhum: não há medida. A rota tem de dizer isso, e nao "0% de CPU" -- o nó
		// existe no catálogo, então 404 seria mentira no outro sentido.
		mockMvc.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.freshness.state").value("NO_SAMPLES"))
			.andExpect(jsonPath("$.summary.sampleCount").value(0));
	}

	@Test
	void oPainelDeNoExigeAdminENoDesconhecidoEh404() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "guarda");

		mockMvc.perform(get("/admin/network/nodes/" + node.id() + "/observability"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/network/nodes/nd_inexistente/observability").header("X-Session-Token", admin))
			.andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- apoio

	private record Node(String id, String key) {
	}

	private void heartbeat(Node node, double cpuPercent, List<Map<String, String>> logs) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("runtimeVersion", "0.1.0");
		body.put("observed", Map.of("cpuPercent", cpuPercent, "memoryMb", 1024, "runningTasks", 0));
		if (!logs.isEmpty()) {
			body.put("logs", logs);
		}
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isOk());
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_panel_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private Node activeNode(String admin, String name) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "no-" + name,
					"ownerUserId", "owner-panel-" + name, "profiles", List.of("NODE_WORKER")))))
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
}