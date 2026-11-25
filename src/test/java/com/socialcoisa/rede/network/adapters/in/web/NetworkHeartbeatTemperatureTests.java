package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
 * Temperatura da máquina do nó (brief §8.2, "quando disponível") do heartbeat ao quadro do nó.
 *
 * <p>Quem mede é o runtime: o Control Plane não abre conexão para dentro da máquina de quem
 * participa (brief §17). O nó empurra a <b>maior</b> leitura entre os sensores no heartbeat que já
 * faz.</p>
 *
 * <p><b>Ausência não é zero.</b> Máquina virtual, container sem o {@code /sys} do host e sessão sem
 * permissão de leitura não têm sensor — e aí o campo não vai. 0 °C seria uma temperatura lida que
 * ninguém leu, e viraria um gráfico de sensor inexistente. Além disso, a medida tem uma casa: 39,6 °C
 * e 39,4 °C são temperaturas diferentes, e a coluna guarda o número digitalizado, não um
 * arredondamento binário que as confundiria.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkHeartbeatTemperatureTests {

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
	void temperaturaMedidaChegaAoSemEaoQuadroDoNo() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "quente");

		heartbeat(node, Map.of("cpuPercent", 10.0, "temperatureC", 39.6));

		assertThat(dbTemperature(node, "observed_temperature_c")).isEqualByComparingTo("39.6");

		var panel = mockMvc
			.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.series[0].temperatureC").value(39.6))
			.andExpect(jsonPath("$.summary.temperatureCLatest").value(39.6))
			.andReturn();
		assertThat(panel.getResponse().getContentAsString()).contains("\"temperatureC\":39.6");
	}

	/**
	 * A casa decimal é significativa: 39,6 °C e 39,4 °C são temperaturas diferentes e a coluna tem de
	 * guardar o valor medido, não um arredondamento que junte as duas.
	 */
	@Test
	void aCasaDecimalEDaMedidaENaoDoArredondamento() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "casa");

		heartbeat(node, Map.of("cpuPercent", 10.0, "temperatureC", 39.6));
		assertThat(dbTemperature(node, "observed_temperature_c")).isEqualByComparingTo("39.6");

		heartbeat(node, Map.of("cpuPercent", 10.0, "temperatureC", 39.4));
		assertThat(dbTemperature(node, "observed_temperature_c")).isEqualByComparingTo("39.4");
	}

	@Test
	void semSensorOTemperaturaFicaDesconhecida() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "sem-sensor");

		heartbeat(node, Map.of("cpuPercent", 5.0, "memoryMb", 1024));

		assertThat(dbTemperature(node, "observed_temperature_c")).isNull();

		var panel = mockMvc
			.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var tree = objectMapper.readTree(panel.getResponse().getContentAsString());
		assertThat(tree.get("series").get(0).get("temperatureC").isNull())
			.as("máquina sem sensor legível não tem temperatura, e 0 afirmaria uma")
			.isTrue();
		assertThat(tree.get("summary").get("temperatureCLatest").isNull()).isTrue();
	}

	private void heartbeat(Node node, Map<String, Object> observed) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("runtimeVersion", "0.1.0");
		body.put("observed", observed);
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isOk());
	}

	private BigDecimal dbTemperature(Node node, String column) {
		return jdbc.queryForObject("select " + column + " from network_nodes where id = ?", BigDecimal.class,
			node.id());
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_temp_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private Node activeNode(String admin, String name) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "no-" + name,
					"ownerUserId", "owner-temp-" + name, "profiles", List.of("NODE_WORKER")))))
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
