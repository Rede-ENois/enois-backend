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
 * IOPS e latência de disco: o que o nó <b>mediu</b> chega pelo heartbeat e aparece no quadro do nó.
 *
 * <p>O brief §8.2 pede as duas métricas por nó, e o runtime é o único que pode medi-las: o Control
 * Plane não abre conexão para dentro da máquina do participante (brief §17). O caminho é o mesmo
 * das outras medidas — o nó empurra a amostra no heartbeat que já faz.</p>
 *
 * <p><b>Zero e ausência são coisas diferentes.</b> Um nó que mediu o disco e não viu nenhuma
 * operação no intervalo tem IOPS 0.0 — é uma medida. Um nó que não mediu (runtime antigo, primeira
 * volta sem amostra anterior) tem nulo — não se sabe. O painel do §8.7 precisa poder separar os
 * dois, e por isso os dois casos estão fixados aqui.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkHeartbeatDiskIoTests {

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
	void heartbeatGravaIopsELatenciaEOQuadroDoNoAsMostra() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "disco");

		heartbeat(node, Map.of("cpuPercent", 10.0, "memoryMb", 2048, "memoryAvailableMb", 6144,
			"diskIops", 42.5, "diskLatencyMs", 3.25, "diskFreeMb", 9000L));

		assertThat(dbDouble(node, "observed_disk_iops")).isEqualTo(42.5);
		assertThat(dbDouble(node, "observed_disk_latency_ms")).isEqualTo(3.25);

		var panel = mockMvc
			.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.series[0].diskIops").value(42.5))
			.andExpect(jsonPath("$.series[0].diskLatencyMs").value(3.25))
			.andExpect(jsonPath("$.summary.diskIopsLatest").value(42.5))
			.andExpect(jsonPath("$.summary.diskLatencyMsLatest").value(3.25))
			.andReturn();
		assertThat(panel.getResponse().getContentAsString()).contains("\"diskIops\":42.5");
	}

	/**
	 * Zero medido é medida; ausência não é zero. O runtime que mediu o disco e não viu operação no
	 * intervalo manda {@code diskIops=0.0} e <b>não</b> manda latência (não há I/O por onde dividir).
	 * O painel tem de mostrar 0.0 onde houve medida e nulo onde não houve.
	 */
	@Test
	void zeroDeIopsMedidoNaoSeConfundeComAusenciaDeMedida() throws Exception {
		String admin = adminToken();
		Node medido = activeNode(admin, "medido");
		heartbeat(medido, Map.of("cpuPercent", 1.0, "diskIops", 0.0));

		assertThat(dbDouble(medido, "observed_disk_iops")).isZero();
		assertThat(dbDouble(medido, "observed_disk_latency_ms")).isNull();

		var panel = mockMvc
			.perform(get("/admin/network/nodes/" + medido.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.series[0].diskIops").value(0.0))
			.andExpect(jsonPath("$.summary.diskIopsLatest").value(0.0))
			.andReturn();
		var tree = objectMapper.readTree(panel.getResponse().getContentAsString());
		assertThat(tree.get("series").get(0).get("diskLatencyMs").isNull())
			.as("sem I/O no intervalo, latência é desconhecida, não zero")
			.isTrue();
	}

	@Test
	void semMedidaDeDiscoOQuadroDizDesconhecido() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "sem-disco");

		heartbeat(node, Map.of("cpuPercent", 5.0, "memoryMb", 1024, "memoryAvailableMb", 7168));

		assertThat(dbDouble(node, "observed_disk_iops")).isNull();
		assertThat(dbDouble(node, "observed_disk_latency_ms")).isNull();

		var panel = mockMvc
			.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var tree = objectMapper.readTree(panel.getResponse().getContentAsString());
		assertThat(tree.get("summary").get("diskIopsLatest").isNull()).isTrue();
		assertThat(tree.get("summary").get("diskLatencyMsLatest").isNull()).isTrue();
	}

	/**
	 * Medida fisicamente impossível não entra: nada de IOPS negativo nem latência negativa. O
	 * runtime já não manda, mas a borda não pode depender do medidor — um corpo forjado com a chave
	 * do nó gravaria {@code -42.5} IOPS no quadro e na série, que é pior que "não medido".
	 */
	@Test
	void medidaNegativaNoHeartbeatNaoViraMedida() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "disco-negativo");

		heartbeat(node, Map.of("cpuPercent", 5.0, "diskIops", -42.5, "diskLatencyMs", -1.0));

		assertThat(dbDouble(node, "observed_disk_iops")).as("IOPS negativo").isNull();
		assertThat(dbDouble(node, "observed_disk_latency_ms")).as("latência negativa").isNull();
		Double amostra = jdbc.queryForObject(
			"select disk_iops from network_node_samples where node_id = ? order by sampled_at desc limit 1",
			Double.class, node.id());
		assertThat(amostra).as("série também não recebe negativo").isNull();

		var painel = mockMvc
			.perform(get("/admin/network/nodes/" + node.id() + "/observability").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(painel.getResponse().getContentAsString()).doesNotContain("-42.5", "-1.0");
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

	private Double dbDouble(Node node, String column) {
		return jdbc.queryForObject("select " + column + " from network_nodes where id = ?", Double.class, node.id());
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_disk_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private Node activeNode(String admin, String name) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "no-" + name,
					"ownerUserId", "owner-disk-" + name, "profiles", List.of("NODE_WORKER")))))
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