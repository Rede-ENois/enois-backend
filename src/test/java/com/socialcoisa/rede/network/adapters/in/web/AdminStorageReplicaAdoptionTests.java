package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * A rota do healing de "réplica única" (doc 13 §5.10): só admin, e o padrão é seco.
 *
 * <p>O teste de serviço já prova a regra; este prova o que a rota <b>não</b> deixa acontecer sem
 * credencial de administrador — pedir transferência de banda de um participante é ato de operador, e
 * uma conta comum chamando aqui faria a rede mover dados em nome de quem contribui.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminStorageReplicaAdoptionTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@org.junit.jupiter.api.BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void sem_admin_a_leitura_e_a_adocao_sao_recusadas() throws Exception {
		var sessao = auth.authenticate("adocao_nao_admin");

		int leitura = mockMvc.perform(get("/admin/network/storage/replica-zero")
			.header("X-Session-Token", sessao.token())).andReturn().getResponse().getStatus();
		int adocao = mockMvc.perform(post("/admin/network/storage/replica-zero")
			.header("X-Session-Token", sessao.token())).andReturn().getResponse().getStatus();

		assertThat(leitura).as("conta comum não enumera o que está só na réplica 0").isIn(401, 403);
		assertThat(adocao).as("nem manda pedir cópia").isIn(401, 403);
	}

	@Test
	void o_dry_run_mostra_o_nome_do_objeto_que_ficou_sem_designacao() throws Exception {
		// Objeto gravado e servível, sem nó elegível (nenhum nó na instalação): é o caso real do
		// upload que terminou só na réplica 0. O operador precisa ver o NOME para decidir, e precisa
		// saber que nada foi gravado — daí o 200 mesmo com objeto na lista.
		jdbc.update("delete from network_nodes");
		String sha = com.socialcoisa.rede.shared.security.Hashes.sha256Hex("corpo");
		jdbc.update("""
			insert into network_storage_objects
				(id, bucket, object_key, content_type, bytes, sha256, state, created_by, created_at, updated_at)
			values ('sob_replica_zero_web', 'fotos', 'gato.jpg', 'image/jpeg', 5, ?, 'STORED', 'user_1', ?, ?)
			""", sha, java.sql.Timestamp.from(java.time.Instant.now()), java.sql.Timestamp.from(java.time.Instant.now()));
		var sessao = auth.authenticate("adocao_admin_dry");
		TestDatabase.setAdmin(sessao.userId());

		var seco = mockMvc.perform(post("/admin/network/storage/replica-zero")
			.header("X-Session-Token", sessao.token())).andReturn();

		assertThat(seco.getResponse().getStatus()).as(seco.getResponse().getContentAsString()).isEqualTo(200);
		var corpo = objectMapper.readValue(seco.getResponse().getContentAsString(), Map.class);
		assertThat(corpo.get("considered")).isEqualTo(1);
		var pulados = (java.util.List<Map<String, Object>>) corpo.get("skipped");
		assertThat(pulados).extracting(p -> p.get("reason")).containsExactly("NO_NODE_ELIGIBLE");
		assertThat(pulados.get(0).get("key")).isEqualTo("gato.jpg");
	}

	@Test
	void o_admin_ve_a_lista_e_a_adocao_sem_corpo_roda_em_seco() throws Exception {
		var sessao = auth.authenticate("adocao_admin");
		TestDatabase.setAdmin(sessao.userId());

		var leitura = mockMvc.perform(get("/admin/network/storage/replica-zero")
			.header("X-Session-Token", sessao.token())).andReturn();
		assertThat(leitura.getResponse().getStatus()).isEqualTo(200);
		var corpo = objectMapper.readValue(leitura.getResponse().getContentAsString(), Map.class);
		assertThat(corpo).containsKeys("considered", "adopted", "skipped");

		// Sem `dryRun=false` a rota não grava nada — o padrão do módulo é mostrar antes.
		var seco = mockMvc.perform(post("/admin/network/storage/replica-zero")
			.header("X-Session-Token", sessao.token())).andReturn();
		assertThat(seco.getResponse().getStatus()).isEqualTo(200);
	}
}