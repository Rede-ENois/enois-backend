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

	/** Segundo objeto do caso: o seco tem de contar o cenário, não o banco. */
	private String objetoDeOutroCaso;

	@org.junit.jupiter.api.BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		// O relatório é do catálogo inteiro, não do cenário: sem uma segunda linha viva ao lado da
		// escolhida, `considered` não distingue "só o meu" de "o meu e o resto do banco". A limpeza
		// atinge só os objetos que este teste criou — a colocação cai junto pelo cascade da FK.
		jdbc.update("delete from network_storage_objects where id like 'rz!_%' escape '!'");
		String sha = com.socialcoisa.rede.shared.security.Hashes.sha256Hex("vazamento");
		objetoDeOutroCaso = "rz_outro_" + java.util.UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
			insert into network_storage_objects
				(id, bucket, object_key, content_type, bytes, sha256, state, created_by, created_at, updated_at)
			values (?, 'fotos', 'outro.jpg', 'image/jpeg', 5, ?, 'STORED', 'user_1', ?, ?)
			""", objetoDeOutroCaso, sha, java.sql.Timestamp.from(java.time.Instant.now()),
			java.sql.Timestamp.from(java.time.Instant.now()));
	}

	@Test
	void sem_admin_a_leitura_e_a_adocao_sao_recusadas() throws Exception {
		var sessao = auth.authenticate("adocao_nao_admin");

		int leitura = mockMvc.perform(get("/admin/network/storage/replica-zero")
			.header("X-Session-Token", sessao.token())).andReturn().getResponse().getStatus();
		// Com `dryRun=false`: a rota de escrita só passa pelo guarda quando vai gravar, e um seco sem
		// alvo não prova que a recusa existe no caminho que grava.
		int adocao = mockMvc.perform(post("/admin/network/storage/replica-zero?dryRun=false")
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
		String objeto = "rz_web_" + java.util.UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
			insert into network_storage_objects
				(id, bucket, object_key, content_type, bytes, sha256, state, created_by, created_at, updated_at)
			values (?, 'fotos', 'gato.jpg', 'image/jpeg', 5, ?, 'STORED', 'user_1', ?, ?)
			""", objeto, sha, java.sql.Timestamp.from(java.time.Instant.now()),
			java.sql.Timestamp.from(java.time.Instant.now()));
		var sessao = auth.authenticate("adocao_admin_dry");
		TestDatabase.setAdmin(sessao.userId());

		var seco = mockMvc.perform(post("/admin/network/storage/replica-zero")
			.header("X-Session-Token", sessao.token())).andReturn();

		assertThat(seco.getResponse().getStatus()).as(seco.getResponse().getContentAsString()).isEqualTo(200);
		var corpo = objectMapper.readValue(seco.getResponse().getContentAsString(), Map.class);
		// O seco relata o catálogo inteiro, inclusive o que outras classes deixaram: o que este caso
		// prova é que o objeto dele aparece com o motivo certo, não que seja o único no banco.
		assertThat((Integer) corpo.get("considered")).isGreaterThanOrEqualTo(2);
		var pulados = (java.util.List<Map<String, Object>>) corpo.get("skipped");
		assertThat(pulados).filteredOn(p -> objeto.equals(p.get("objectId")))
			.singleElement()
			.satisfies(p -> {
				assertThat(p.get("reason")).isEqualTo("NO_NODE_ELIGIBLE");
				assertThat(p.get("key")).isEqualTo("gato.jpg");
			});
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