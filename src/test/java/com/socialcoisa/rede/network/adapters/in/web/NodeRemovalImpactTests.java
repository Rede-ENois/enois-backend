package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import tools.jackson.databind.ObjectMapper;

/**
 * O preço de revogar um nó de armazenamento, lido pela rota (brief §17, "remover nó da rede").
 *
 * <p>Revogar não tem volta: as chaves caem na mesma transação e o {@code approve} recusa o retorno.
 * Até o ciclo 73 nada dizia o que o nó levava junto — e a varredura continuava contando a cópia dele
 * como durabilidade, então o objeto que ficava só com a réplica 0 <b>não</b> aparecia como lacuna.</p>
 *
 * <p>O que estas asserções seguram: o objeto cuja última cópia de nó é deste nó aparece; o objeto
 * que tem outra cópia viva não aparece; e a conta usa o estado do <b>outro</b> nó — uma cópia num nó
 * já revogado não é saída.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NodeRemovalImpactTests {

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
		var session = auth.authenticate("net_impact_admin_" + System.nanoTime());
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	/** Nó completo pelo caminho real: provisionamento, aprovação e chave. */
	private String node(String nome, String admin) throws Exception {
		var token = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", nome, "ownerUserId", "owner-" + nome,
					"profiles", List.of("NODE_STORAGE")))))
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
		return nodeId;
	}

	private String objeto(String chave, String estado) {
		String id = "sob_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		jdbc.update("""
			insert into network_storage_objects (id, bucket, object_key, content_type, bytes, sha256, state,
			  created_by, created_at, updated_at)
			values (?, 'fotos', ?, 'image/jpeg', 8, ?, ?, 'u', now(3), now(3))
			""", id, chave, "a".repeat(64), estado);
		return id;
	}

	private void colocacao(String objectId, int replica, String nodeId, String state) {
		jdbc.update("""
			insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
			values (?, ?, ?, 8, ?, ?, now(3))
			""", objectId, replica, nodeId, "a".repeat(64), state);
	}

	private Map leitura(String admin, String nodeId) throws Exception {
		var resposta = mockMvc.perform(get("/admin/network/nodes/" + nodeId + "/removal-impact")
			.header("X-Session-Token", admin)).andReturn();
		assertThat(resposta.getResponse().getStatus()).as(resposta.getResponse().getContentAsString()).isEqualTo(200);
		return objectMapper.readValue(resposta.getResponse().getContentAsString(), Map.class);
	}

	@Test
	void o_objeto_sem_outra_copia_de_no_aparece_como_perdido() throws Exception {
		String admin = adminToken();
		String alvo = node("nd_impact_alvo", admin);
		// Só a réplica 0 (o Control Plane) e esta cópia: revogar deixa o objeto sem cópia de nó.
		String so = objeto("so-aqui.jpg", "STORED");
		colocacao(so, 0, "nd_replica_zero", "OK");
		colocacao(so, 1, alvo, "OK");

		Map corpo = leitura(admin, alvo);

		assertThat(corpo.get("objectsHeld")).isEqualTo(1);
		assertThat(corpo.get("objectsLosingLastNodeCopy")).isEqualTo(1);
		assertThat(corpo.get("warning")).isEqualTo(true);
		var perdidos = (List<Map<String, Object>>) corpo.get("losesLastCopy");
		assertThat(perdidos).singleElement().satisfies(p -> {
			assertThat(p.get("objectId")).isEqualTo(so);
			assertThat(p.get("key")).isEqualTo("so-aqui.jpg");
			assertThat(p.get("replica")).isEqualTo(1);
		});
		assertThat(corpo.get("truncated")).isEqualTo(false);
	}

	@Test
	void o_objeto_com_outra_copia_viva_nao_aparece() throws Exception {
		String admin = adminToken();
		String alvo = node("nd_impact_compar", admin);
		String outro = node("nd_impact_outro", admin);
		String objeto = objeto("duas-copias.jpg", "STORED");
		colocacao(objeto, 1, alvo, "OK");
		colocacao(objeto, 2, outro, "OK");

		Map corpo = leitura(admin, alvo);

		assertThat(corpo.get("objectsHeld")).isEqualTo(1);
		assertThat(corpo.get("objectsLosingLastNodeCopy")).as("a cópia do outro nó continua").isEqualTo(0);
		assertThat(corpo.get("warning")).isEqualTo(false);
	}

	@Test
	void a_copia_do_outro_so_conta_se_o_outro_no_ainda_serve() throws Exception {
		String admin = adminToken();
		String alvo = node("nd_impact_vivo", admin);
		String morto = node("nd_impact_morto", admin);
		String objeto = objeto("so-no-morto.jpg", "STORED");
		colocacao(objeto, 1, alvo, "OK");
		colocacao(objeto, 2, morto, "OK");
		mockMvc.perform(post("/admin/network/nodes/" + morto + "/status").header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("status", "REVOKED"))))
			.andExpect(status().isOk());

		Map corpo = leitura(admin, alvo);

		assertThat(corpo.get("objectsLosingLastNodeCopy"))
			.as("revogado o outro, a última cópia viva é deste nó").isEqualTo(1);
	}

	@Test
	void copia_em_estado_que_nao_confirma_nao_conta_como_guardada() throws Exception {
		String admin = adminToken();
		String alvo = node("nd_impact_stale", admin);
		String objeto = objeto("stale.jpg", "STORED");
		colocacao(objeto, 1, alvo, "STALE");

		Map corpo = leitura(admin, alvo);

		assertThat(corpo.get("objectsHeld")).as("cópia STALE não é o que o nó guarda de bom")
			.isEqualTo(0);
		assertThat(corpo.get("warning")).isEqualTo(false);
	}

	@Test
	void objeto_ja_removido_nao_entra_na_conta() throws Exception {
		String admin = adminToken();
		String alvo = node("nd_impact_lapide", admin);
		String objeto = objeto("removido.jpg", "DELETED");
		colocacao(objeto, 1, alvo, "OK");

		Map corpo = leitura(admin, alvo);

		assertThat(corpo.get("objectsLosingLastNodeCopy")).as("lápide não é algo que alguém baixe")
			.isEqualTo(0);
	}

	/**
	 * A réplica 0 mora no Control Plane, que é um <b>domínio próprio</b> — mas não é cópia de nó, e o
	 * brief conta durabilidade por cópia de nó. Aqui o Control Plane também está cadastrado como nó
	 * ativo: sem o filtro por réplica, a conta diria "tem outra cópia viva" e o alerta não sairia.
	 */
	@Test
	void a_replica_zero_nao_conta_como_copia_de_no() throws Exception {
		String admin = adminToken();
		String alvo = node("nd_impact_r0", admin);
		String controlPlane = node("nd_impact_cp", admin);
		String objeto = objeto("com-r0.jpg", "STORED");
		colocacao(objeto, 0, controlPlane, "OK");
		colocacao(objeto, 1, alvo, "OK");

		Map corpo = leitura(admin, alvo);

		assertThat(corpo.get("objectsLosingLastNodeCopy"))
			.as("a réplica 0 não substitui cópia de nó").isEqualTo(1);
	}

	@Test
	void no_ja_revogado_nao_avisa_de_novo() throws Exception {
		String admin = adminToken();
		String alvo = node("nd_impact_ja", admin);
		String objeto = objeto("so-aqui-2.jpg", "STORED");
		colocacao(objeto, 1, alvo, "OK");
		mockMvc.perform(post("/admin/network/nodes/" + alvo + "/status").header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("status", "REVOKED"))))
			.andExpect(status().isOk());

		Map corpo = leitura(admin, alvo);

		assertThat(corpo.get("objectsLosingLastNodeCopy")).isEqualTo(1);
		assertThat(corpo.get("warning")).as("o dano já está feito: avisar de novo não ajuda").isEqualTo(false);
	}

	@Test
	void suspender_nao_conta_como_saida() throws Exception {
		String admin = adminToken();
		String alvo = node("nd_impact_susp", admin);
		String objeto = objeto("suspenso.jpg", "STORED");
		colocacao(objeto, 1, alvo, "OK");

		var resposta = mockMvc.perform(get("/admin/network/nodes/" + alvo + "/removal-impact?planned=SUSPENDED")
			.header("X-Session-Token", admin)).andReturn();

		Map corpo = objectMapper.readValue(resposta.getResponse().getContentAsString(), Map.class);
		assertThat(corpo.get("plannedStatus")).isEqualTo("SUSPENDED");
		assertThat(corpo.get("warning")).isEqualTo(false);
	}

	@Test
	void sem_admin_nao_le() throws Exception {
		var sessao = auth.authenticate("net_impact_nao_admin_" + System.nanoTime());

		int status = mockMvc.perform(get("/admin/network/nodes/nd_qualquer/removal-impact")
			.header("X-Session-Token", sessao.token())).andReturn().getResponse().getStatus();

		assertThat(status).isIn(401, 403);
	}

	@Test
	void no_inexistente_responde_404() throws Exception {
		String admin = adminToken();

		int status = mockMvc.perform(get("/admin/network/nodes/nd_nao_existe/removal-impact")
			.header("X-Session-Token", admin)).andReturn().getResponse().getStatus();

		assertThat(status).isEqualTo(404);
	}
}