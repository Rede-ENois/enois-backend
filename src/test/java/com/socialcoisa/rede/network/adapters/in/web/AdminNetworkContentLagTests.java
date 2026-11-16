package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * O atraso de sincronização de <b>outro</b> nó, visto pelo administrador (brief §8.4/§8.6).
 *
 * <p>O nó já podia perguntar o próprio atraso; o operador não via o de ninguém, e "a réplica X está
 * atrasada" só era sabível entrando na máquina. O que estes testes seguram:</p>
 * <ul>
 * <li>o atraso do nó aparece para o admin, com <b>todos</b> os tipos — inclusive os em dia, porque o
 * tipo ausente esconderia qual conjunto foi medido;</li>
 * <li>nó desconhecido é <b>404</b>, não lista zerada: "não está atrasado" e "não existe" não podem ser
 * a mesma resposta;</li>
 * <li>sem sessão de admin a rota não abre.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminNetworkContentLagTests {

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
		jdbc.update("delete from network_content_snapshots");
		jdbc.update("delete from network_content_versions");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_lag_admin_" + System.nanoTime());
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private record Node(String id, String key) {
	}

	private Node node(String nome) throws Exception {
		String admin = adminToken();
		var token = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", nome, "ownerUserId", "owner-" + nome,
					"profiles", List.of("NODE_EDGE")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(token.getResponse().getContentAsString()).get("token").asString();
		var enroll = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 2, "memoryMb", 4096, "diskMb", 102400, "bandwidthMbps", 100)))))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(enroll.getResponse().getContentAsString()).get("nodeId").asString();
		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());
		var chave = mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		String key = objectMapper.readTree(chave.getResponse().getContentAsString()).get("apiKey").asString();
		return new Node(nodeId, key);
	}

	@Test
	void o_admin_ve_o_atraso_de_outro_no_por_tipo() throws Exception {
		Node no = node("nd_atrasado");
		String admin = adminToken();

		var resposta = mockMvc
			.perform(get("/admin/network/content/nodes/" + no.id() + "/lag").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var tipos = objectMapper.readTree(resposta.getResponse().getContentAsString());

		// Nó sem snapshot está atrasado em TUDO que a origem tem; e todos os tipos aparecem, inclusive
		// os que estariam em dia — o tipo ausente esconderia qual conjunto foi medido.
		assertThat(tipos.isArray()).isTrue();
		assertThat(tipos.size()).isGreaterThanOrEqualTo(2);
		for (var tipo : tipos) {
			assertThat(tipo.has("kind")).isTrue();
			assertThat(tipo.has("behind")).isTrue();
			assertThat(tipo.get("behind").asLong()).isGreaterThanOrEqualTo(0);
		}
	}

	@Test
	void no_desconhecido_e_404_e_nao_lista_zerada() throws Exception {
		String admin = adminToken();

		// "Não está atrasado" e "não existe" não podem ser a mesma resposta: o primeiro afirma saúde
		// sobre um nó que pode nem existir.
		mockMvc.perform(get("/admin/network/content/nodes/nd_inexistente/lag").header("X-Session-Token", admin))
			.andExpect(status().isNotFound());
	}

	@Test
	void sem_sessao_de_admin_a_rota_nao_abre() throws Exception {
		Node no = node("nd_guarda");

		mockMvc.perform(get("/admin/network/content/nodes/" + no.id() + "/lag"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void pessoa_comum_nao_ve_o_atraso_de_ninguem() throws Exception {
		Node no = node("nd_alheio");
		// Sessão VÁLIDA, mas de quem não é admin: é o caso que a falta de sessão não cobre — o
		// usuário autenticado não pode ler a saúde dos nós de terceiros pela porta do painel.
		//
		// Este teste fixa o FECHAMENTO da porta, não o `admin()` da rota: o `AdminRouteInterceptor`
		// já exige admin em `/admin/**`, e tirar o `admin()` do controller não abre nada (defesa em
		// profundidade, verificada por mutação). Quem abrir o caminho é a mudança do interceptor.
		String comum = auth.authenticate("net_lag_comum_" + System.nanoTime()).token();

		mockMvc.perform(get("/admin/network/content/nodes/" + no.id() + "/lag").header("X-Session-Token", comum))
			.andExpect(status().isForbidden());
	}
}