package com.socialcoisa.rede.network.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import org.junit.jupiter.api.Tag;
import tools.jackson.databind.ObjectMapper;

/**
 * O teto de chave de nó errada, pela rota real.
 *
 * <p><b>Por que de rota.</b> O limite só vale se estiver no caminho de autenticação de
 * <b>todas</b> as rotas de nó — e é o {@code NodeKeyService} que está. Um teste do serviço provaria a
 * conta; este prova que a requisição HTTP de fato para de chegar à comparação de hash depois do
 * teto, e que o vizinho (outro nó) não paga pelo erro de quem atacou.</p>
 */
@Tag("critico")
@SpringBootTest
@AutoConfigureMockMvc
class NetworkNodeAuthRateLimitTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private com.socialcoisa.rede.shared.security.IpRateLimiter tentativas;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		// O limitador é um bean único do contexto: sem zerar, a conta de outro teste escolheria o
		// resultado deste.
		tentativas.limpar();
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	/** Nó completo pelo caminho real, com a chave que a resposta devolve. */
	private Node node() throws Exception {
		String admin = adminToken();
		String name = "auth-" + UUID.randomUUID().toString().substring(0, 8);
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", name, "ownerUserId", "owner-" + name,
					"profiles", List.of("NODE_WORKER")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString()).get("token")
			.asString();
		var enroll = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 1, "memoryMb", 512, "diskMb", 1024, "bandwidthMbps", 10)))))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(enroll.getResponse().getContentAsString()).get("nodeId").asString();
		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());
		var keyResult = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		String key = objectMapper.readTree(keyResult.getResponse().getContentAsString()).get("apiKey").asString();
		return new Node(nodeId, key);
	}

	record Node(String id, String key) {
	}

	/** Uma chamada autenticada de nó: o heartbeat é a rota que todo runtime bate. */
	private org.springframework.test.web.servlet.ResultActions heartbeat(Node node, String key) throws Exception {
		return mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id()).header("X-Node-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
				Map.of("cpuPercent", 1.0, "memoryMb", 128, "runningTasks", 0)))));
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_auth_limit_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	@Test
	void a_chave_errada_chega_ao_teto_e_o_no_legitimo_nao_paga() throws Exception {
		Node alvo = node();
		Node vizinho = node();

		// Dez falhas por cinco minutos é o padrão (`rede.security.rate-limit.node-auth.limit`).
		for (int i = 0; i < 10; i++) {
			heartbeat(alvo, "enk_nd_errada_" + i).andExpect(status().isUnauthorized());
		}
		// A décima primeira não chega a comparar hash: o teto responde 429 com código próprio — se
		// respondesse 401, quem opera não saberia que o bloqueio é de tentativas.
		heartbeat(alvo, alvo.key()).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("NODE_AUTH_RATE_LIMITED"));

		// O vizinho, com a chave certa, continua entrando: o ataque a um nó não derruba a rede.
		heartbeat(vizinho, vizinho.key()).andExpect(status().isOk());
	}

	@Test
	void o_no_que_so_acerta_nunca_e_barrado() throws Exception {
		Node node = node();
		// Vinte acertos seguidos: nenhum deles gasta orçamento de tentativa (o acerto é do dono).
		for (int i = 0; i < 20; i++) {
			heartbeat(node, node.key()).andExpect(status().isOk());
		}
	}

	@Test
	void a_chave_certa_depois_de_nove_erros_ainda_entra() throws Exception {
		Node node = node();
		for (int i = 0; i < 9; i++) {
			heartbeat(node, "enk_nd_errada_" + i).andExpect(status().isUnauthorized());
		}
		// Ainda abaixo do teto: o dono corrige a chave e entra.
		heartbeat(node, node.key()).andExpect(status().isOk());
	}
}