package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Detecção de mudança de IP (brief §5), medida no banco.
 *
 * <p>O servidor doméstico atrás de NAT não informa endereço: quem vê a origem é a borda do
 * heartbeat. O que estes testes guardam é a <b>transição</b> — o mesmo IP batendo coração não é
 * mudança, e o primeiro contato também não é (não houve troca, houve conhecimento). Se a coluna de
 * mudança fosse reescrita a cada heartbeat, o painel diria "mudou há 15 segundos" para sempre.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkNodeObservedIpTests {

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

	/** O primeiro contato grava o IP e deixa a mudança NULA — conhecer não é mudar. */
	@Test
	void primeiro_contato_grava_o_ip_sem_marcar_mudanca() throws Exception {
		Node node = storageNode("nd_ip1");

		heartbeat(node, "203.0.113.10");

		assertThat(observedIp(node.id())).isEqualTo("203.0.113.10");
		assertThat(changedAt(node.id())).isNull();
	}

	/** Mesmo IP em três heartbeats: nada muda, e a marca de mudança continua nula. */
	@Test
	void mesmo_ip_nao_e_mudanca() throws Exception {
		Node node = storageNode("nd_ip2");

		heartbeat(node, "203.0.113.20");
		heartbeat(node, "203.0.113.20");
		heartbeat(node, "203.0.113.20");

		assertThat(observedIp(node.id())).isEqualTo("203.0.113.20");
		assertThat(changedAt(node.id())).isNull();
	}

	/** IP diferente marca a mudança e atualiza o endereço. */
	@Test
	void ip_diferente_marca_a_mudanca() throws Exception {
		Node node = storageNode("nd_ip3");

		heartbeat(node, "203.0.113.30");
		heartbeat(node, "198.51.100.7");

		assertThat(observedIp(node.id())).isEqualTo("198.51.100.7");
		assertThat(changedAt(node.id())).isNotNull();
	}

	/**
	 * O instante da mudança NÃO anda quando o IP repete depois da troca: é o caso de quem mudou uma
	 * vez e ficou estável. Se a query reescrevesse a marca a cada heartbeat, esta asserção ficaria
	 * vermelha.
	 */
	@Test
	void a_marca_de_mudanca_para_de_andar_quando_o_ip_estabiliza() throws Exception {
		Node node = storageNode("nd_ip4");
		heartbeat(node, "203.0.113.40");
		heartbeat(node, "198.51.100.8");
		Object primeira = changedAt(node.id());

		heartbeat(node, "198.51.100.8");
		heartbeat(node, "198.51.100.8");

		assertThat(changedAt(node.id())).isEqualTo(primeira);
	}

	/** `X-Forwarded-For` vence o socket: atrás do proxy, o socket fala com o proxy. */
	@Test
	void x_forwarded_for_tem_prioridade_sobre_o_socket() throws Exception {
		Node node = storageNode("nd_ip5");

		heartbeatCom(node, "203.0.113.55, 10.0.0.1", "10.0.0.1");

		assertThat(observedIp(node.id())).isEqualTo("203.0.113.55");
	}

	private record Node(String id, String key) {
	}

	private void heartbeat(Node node, String forwardedFor) throws Exception {
		heartbeatCom(node, forwardedFor, "127.0.0.1");
	}

	private void heartbeatCom(Node node, String forwardedFor, String remote) throws Exception {
		var request = post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key());
		if (forwardedFor != null) {
			request = request.header("X-Forwarded-For", forwardedFor);
		}
		var result = mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0",
				"observed", Map.of("cpuPercent", 1.0)))))
			.andReturn();
		if (result.getResponse().getStatus() != 200) {
			throw new AssertionError("heartbeat " + result.getResponse().getStatus() + ": "
				+ result.getResponse().getContentAsString());
		}
	}

	private String observedIp(String id) {
		return jdbc.queryForObject("select observed_ip from network_nodes where id = ?",
			(rs, row) -> rs.getString(1), id);
	}

	private Object changedAt(String id) {
		return jdbc.queryForObject("select observed_ip_changed_at from network_nodes where id = ?",
			(rs, row) -> rs.getTimestamp(1), id);
	}

	private Node storageNode(String displayName) throws Exception {
		var session = auth.authenticate("dono_" + displayName);
		TestDatabase.setAdmin(session.userId());
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", displayName,
					"ownerUserId", session.userId(), "profiles", List.of("NODE_STORAGE")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
			.get("token").asString();
		var enroll = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 2, "memoryMb", 4096, "diskMb", 51200, "bandwidthMbps", 50)))))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(enroll.getResponse().getContentAsString()).get("nodeId").asString();
		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", session.token()))
			.andExpect(status().isOk());
		var keyResult = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		return new Node(nodeId, objectMapper.readTree(keyResult.getResponse().getContentAsString())
			.get("apiKey").asString());
	}
}
