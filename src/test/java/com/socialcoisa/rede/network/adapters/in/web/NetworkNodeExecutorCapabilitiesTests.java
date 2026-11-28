package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * A declaração de capacidade do executor (brief §8.2, "Health checks").
 *
 * <p>A config assinada autoriza tipos por perfil, e permissão não é capacidade: a máquina
 * {@code NODE_MEDIA} pode não ter o {@code ffmpeg}. Sem a declaração, o Control Plane designa, o nó
 * tenta, falta o binário e a tarefa gasta uma tentativa — uma por ciclo. O que estes testes fixam:
 * a declaração grava (V390), só entra o que a config autoriza, e "não declarou" deixa a regra como
 * era — só a permissão do perfil.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkNodeExecutorCapabilitiesTests {

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
	void aDeclaracaoGravaOsTiposQueAConfigAutoriza() throws Exception {
		// NODE_MEDIA autoriza MEDIA_TRANSCODE: a declaração de um tipo autorizado é o caso normal.
		Node node = activeNode("declara", List.of("NODE_MEDIA"));

		declare(node, List.of("MEDIA_TRANSCODE"))
			.andExpect(status().isOk())
			// A config de um NODE_MEDIA autoriza mais do que transcode; a declaração é a que o nó
			// mandou, e só ela — o Control Plane não completa a lista por conta própria.
			.andExpect(jsonPath("$.enabledTaskTypes[0]").value("MEDIA_TRANSCODE"));

		String coluna = jdbc.queryForObject("select executor_task_types from network_nodes where id = ?",
			String.class, node.id());
		assertThat(coluna).as("JSON array do que foi declarado").isEqualTo("[\"MEDIA_TRANSCODE\"]");
	}

	@Test
	void tipoQueAConfigNaoAutorizaNaoEntra() throws Exception {
		// NODE_WORKER não autoriza MEDIA_TRANSCODE: declarar não muda a permissão assinada.
		Node node = activeNode("fora-do-perfil", List.of("NODE_WORKER"));

		declare(node, List.of("MEDIA_TRANSCODE"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.enabledTaskTypes.length()").value(0));

		assertThat(jdbc.queryForObject("select executor_task_types from network_nodes where id = ?", String.class,
			node.id())).as("a coluna grava a lista vazia, não o tipo recusado").isEqualTo("[]");
	}

	@Test
	void declararNadaErespeitadoComoNenhumTipo() throws Exception {
		Node node = activeNode("nada", List.of("NODE_MEDIA"));

		declare(node, List.of()).andExpect(status().isOk()).andExpect(jsonPath("$.enabledTaskTypes.length()").value(0));

		assertThat(jdbc.queryForObject("select executor_task_types from network_nodes where id = ?", String.class,
			node.id())).isEqualTo("[]");
	}

	@Test
	void oNoUtilizaACapacidadeDeclaradaEAusenteNaoBLOQUEIA() {
		// Domínio puro: a regra que o despacho aplica. Sem declaração, a permissão do perfil decide
		// (runtime antigo não sai do balanceamento); com declaração vazia, o nó se declarou incapaz.
		NetworkNode declarouNada = no(List.of());
		NetworkNode declarouTranscode = no(List.of("MEDIA_TRANSCODE"));
		NetworkNode nuncaDeclarou = no(null);

		assertThat(declarouNada.able("MEDIA_TRANSCODE")).as("declarou nada: respeitado").isFalse();
		assertThat(nuncaDeclarou.able("MEDIA_TRANSCODE")).as("não declarou: a permissão vale").isTrue();
		assertThat(declarouTranscode.able("MEDIA_TRANSCODE")).isTrue();
		assertThat(declarouTranscode.able("READ_QUERY")).as("tipo fora da declaração").isFalse();
	}

	@Test
	void aListaDaConfigEaPermissaoSaemDaMesmaRegra() {
		// Se as duas dessem listas diferentes, o nó declararia executável um tipo que a config dele
		// nem autoriza (ou o contrário).
		assertThat(TaskType.allowedNamesFor(java.util.Set.of(NodeProfile.NODE_MEDIA)))
			.isEqualTo(TaskType.allowedTypesFor(java.util.Set.of(NodeProfile.NODE_MEDIA))
				.stream().map(Enum::name).toList());
	}

	private NetworkNode no(List<String> declarados) {
		return new NetworkNode("nd_cap", "no", "user", NodeStatus.ACTIVE, java.util.Set.of(NodeProfile.NODE_MEDIA),
			"sa-east-1", "BR", "0.1.0", java.time.Instant.now(), 4, 8192, 51200, 100, 0.0, 1024, 7168, 0, null, 9000L,
			10240L, null, null, null, null, null, 0, null, null, null, declarados);
	}

	private org.springframework.test.web.servlet.ResultActions declare(Node node, List<String> tipos)
			throws Exception {
		return mockMvc
			.perform(post("/network/nodes/me/executor-capabilities").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("enabledTaskTypes", tipos))));
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_cap_admin");
		com.socialcoisa.rede.shared.web.TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private Node activeNode(String name, List<String> profiles) throws Exception {
		String admin = adminToken();
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "no-" + name,
					"ownerUserId", "owner-cap-" + name, "profiles", profiles))))
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