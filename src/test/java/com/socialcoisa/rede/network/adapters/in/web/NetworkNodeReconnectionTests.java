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
 * O histórico de conexão do nó (brief §8.6): o heartbeat é a única chance de ver a <b>volta</b> de
 * quem ficou fora.
 *
 * <p>O que estes testes seguram, e por quê:</p>
 * <ul>
 * <li>a volta de um nó que ficou mais que a janela de vivo vira evento auditado com o tempo fora —
 * sem ele o painel mostra "conectado agora" e não conta que a máquina caiu;</li>
 * <li>heartbeat normal <b>não</b> gera evento: seria uma linha por ciclo por nó, e a trilha afogaria
 * o que importa;</li>
 * <li>o primeiro contato não é "reconexão": sombrearia um incidente que não houve;</li>
 * <li>o evento sai na rota de histórico do próprio nó, que a página já mostra.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkNodeReconnectionTests {

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
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_storage_objects");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_reconn_admin_" + System.nanoTime());
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private record Node(String id, String key) {
	}

	/** Nó completo pelo caminho real: token → enroll → aprovação → chave. Sem heartbeat ainda. */
	private Node node(String nome) throws Exception {
		String admin = adminToken();
		var token = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", nome, "ownerUserId", "owner-" + nome,
					"profiles", List.of("NODE_WORKER")))))
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

	private void heartbeat(Node node) throws Exception {
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id()).header("X-Node-Key", node.key())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
					Map.of("cpuPercent", 5.0, "memoryMb", 1024, "runningTasks", 0, "diskFreeMb", 51200)))))
			.andExpect(status().isOk());
	}

	private int reconexoes(String nodeId) {
		return jdbc.queryForObject(
			"select count(*) from audit_logs where action = 'NETWORK_NODE_RECONNECTED' and target_id = ?",
			Integer.class, nodeId);
	}

	@Test
	void no_que_volta_depois_da_janela_vira_evento_com_o_tempo_fora() throws Exception {
		Node no = node("nd_volta");
		heartbeat(no);
		// A máquina ficou fora por 21 minutos: o último contato é o que o CP tinha. A folga de 1 min
		// não é decoração — com exatamente 20 min, o `getSeconds()` de uma diferença com fração de
		// segundo trunca para 1199 (falso vermelho intermitente em 1.199 de ~1.200 execuções); 21 min
		// prova o mesmo comportamento com margem de 60 s sobre o piso.
		jdbc.update("update network_nodes set last_seen_at = now(3) - interval '21' minute where id = ?", no.id());

		heartbeat(no);

		assertThat(reconexoes(no.id())).isEqualTo(1);
		String metadata = jdbc.queryForObject(
			"select metadata from audit_logs where action = 'NETWORK_NODE_RECONNECTED' and target_id = ?",
			String.class, no.id());
		// O tempo fora é o dado que o operador precisa: "voltou" sem "por quanto" diz metade.
		assertThat(metadata).contains("offlineForSeconds");
		int fora = Integer.parseInt(metadata.replaceAll(".*\"offlineForSeconds\":(\\d+).*", "$1"));
		assertThat(fora).isGreaterThanOrEqualTo(1200);
	}

	@Test
	void heartbeat_normal_nao_gera_evento_de_conexao() throws Exception {
		Node no = node("nd_saudavel");
		heartbeat(no);
		heartbeat(no);
		heartbeat(no);

		// Uma linha por ciclo por nó afogaria a trilha: o que se guarda é a TRANSIÇÃO.
		assertThat(reconexoes(no.id())).isZero();
	}

	@Test
	void o_primeiro_contato_nao_e_reconexao() throws Exception {
		Node no = node("nd_novo");

		heartbeat(no);

		// Nunca houve contato anterior: chamar isso de "voltou" sombrearia um incidente que não houve.
		assertThat(reconexoes(no.id())).isZero();
	}

	@Test
	void o_evento_aparece_no_historico_do_proprio_no() throws Exception {
		Node no = node("nd_historico");
		heartbeat(no);
		jdbc.update("update network_nodes set last_seen_at = now(3) - interval '1' hour where id = ?", no.id());
		heartbeat(no);

		String admin = adminToken();
		var historico = mockMvc
			.perform(get("/admin/network/nodes/" + no.id() + "/history").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();

		// A página do nó já mostra esta trilha: o histórico de conexão não precisa de rota nem tabela
		// própria — a trilha que prova as ações é a mesma que as conta.
		assertThat(historico.getResponse().getContentAsString()).contains("NETWORK_NODE_RECONNECTED");
	}
}