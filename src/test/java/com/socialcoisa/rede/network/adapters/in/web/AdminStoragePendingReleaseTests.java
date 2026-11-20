package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
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
 * A rota de soltura da designação pendente (doc 13 §5.11): o ato de operador que antes só existia
 * dentro do worker de auto-heal, que nasce desligado.
 *
 * <p>O teste mais importante daqui é o do caminho completo <b>sem worker</b>: a pendência vencida é
 * solta pela rota, e o {@code /storage/heal} passa a designar de novo em vez de responder
 * {@code ALREADY_DESIGNATED} para sempre. É o buraco que a tela do operador tinha — via a durabilidade
 * baixa e não tinha o que apertar.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminStoragePendingReleaseTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	/** Montado com o MockMvc daqui: dá para pegar a sessão e trocar o papel no banco entre chamadas. */
	@Autowired
	private com.socialcoisa.rede.shared.web.CurrentUserResolver currentUserResolver;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private static java.sql.Timestamp ts(java.time.Instant instante) {
		return java.sql.Timestamp.from(instante);
	}

	private void objetoComPendencia(String id, java.time.Instant designada) {
		String sha = com.socialcoisa.rede.shared.security.Hashes.sha256Hex("corpo-" + id);
		var agora = java.time.Instant.now();
		jdbc.update("""
			insert into network_storage_objects
				(id, bucket, object_key, content_type, bytes, sha256, state, created_by, created_at, updated_at)
			values (?, 'fotos', ?, 'image/jpeg', 5, ?, 'STORED', 'user_1', ?, ?)
			""", id, id + ".jpg", sha, ts(agora.minusSeconds(86400)), ts(agora.minusSeconds(86400)));
		jdbc.update("""
			insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
			values (?, 0, 'control-plane', 5, ?, 'OK', ?)
			""", id, sha, ts(agora.minusSeconds(86400)));
		jdbc.update("""
			insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
			values (?, 1, 'nd_perdido', 5, ?, 'PENDING_REMOTE', ?)
			""", id, sha, ts(designada));
	}

	private String estadoDaPendencia(String objeto) {
		return jdbc.queryForObject("""
			select state from network_storage_placements where object_id = ? and replica = 1
			""", String.class, objeto);
	}

	@Test
	void sem_admin_a_leitura_e_a_soltura_sao_recusadas() throws Exception {
		var sessao = auth.authenticate("solta_nao_admin");

		int leitura = mockMvc.perform(get("/admin/network/storage/pending")
			.header("X-Session-Token", sessao.token())).andReturn().getResponse().getStatus();
		int soltura = mockMvc.perform(post("/admin/network/storage/pending/release")
			.header("X-Session-Token", sessao.token())).andReturn().getResponse().getStatus();

		assertThat(leitura).as("conta comum não enumera as transferências em curso").isIn(401, 403);
		assertThat(soltura).as("nem solta a designação de um nó alheio").isIn(401, 403);

		// A rota de escrita com um alvo REAL. Sem esta linha a mutação que tira o `admin()` da rota de
		// soltura continua verde: sem pendência para soltar, o serviço pula o ato de operador e a
		// recusa nunca é exercitada no mesmo caminho que a mutação muda.
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_storage_objects");
		objetoComPendencia("sob_alheia", java.time.Instant.now().minusSeconds(3 * 3600));
		var sessaoComPendencia = auth.authenticate("solta_nao_admin_com_pendencia");

		int real = mockMvc.perform(post("/admin/network/storage/pending/release?dryRun=false")
			.header("X-Session-Token", sessaoComPendencia.token())).andReturn().getResponse().getStatus();

		assertThat(real).as("conta comum não solta a designação real").isIn(401, 403);
		assertThat(estadoDaPendencia("sob_alheia")).isEqualTo("PENDING_REMOTE");
	}

	@Test
	void a_conta_que_so_perdeu_o_admin_e_recusada_pelo_guarda_da_rota() throws Exception {
		// A mutação que tira o `requireAdmin` fica VERDE nas duas recusas acima: o 401/403 vem da
		// própria leitura da sessão, antes de qualquer guarda. O discriminante é uma sessão que CONTINUA
		// válida e cujo papel muda — o guarda é a única coisa que pode barrar o pedido.
		//
		// Duas características moldam o teste:
		// - quem responde 403 aqui é o {@code AdminRouteInterceptor} global (toda rota /admin/**), e não
		//   o {@code requireAdmin} do controller: tirar o do controller NÃO muda a resposta. A cópia
		//   dele no controller fica, como em todo controller de admin do projeto, mas o discriminante
		//   desta rota é a sessão — sem ela é 401, sem o papel é 403;
		// - `rede.admin.bootstrap-enabled=true` nos testes promove `group-admin@test.com` por e-mail,
		//   independentemente de `is_admin`: a conta não pode ser essa, senão continua admin por lá.
		// E a conta não pode acabar admin: `StorageHealer.heal` para o PRIMEIRO admin do banco, e um
		// admin a mais mudaria o resultado dos testes de healing que rodam depois. Por isso o finally.
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_storage_objects");
		objetoComPendencia("sob_guarda", java.time.Instant.now().minusSeconds(3 * 3600));
		var dono = auth.authenticate("solta_dono_do_papel");
		TestDatabase.clearAdmin(dono.userId());
		try {
			var semOPapel = mockMvc.perform(get("/admin/network/storage/pending")
				.header("X-Session-Token", dono.token())).andReturn();
			var solturaSemOPapel = mockMvc.perform(post("/admin/network/storage/pending/release?dryRun=false")
				.header("X-Session-Token", dono.token())).andReturn();

			// 403 (sessão boa, papel recusado), não 401 (sessão): é o que separa "a sessão some" de "a
			// conta não é admin". A conta não é a da allowlist de teste (`group-admin@test.com`), que
			// seria admin por e-mail mesmo com `is_admin` limpo.
			assertThat(semOPapel.getResponse().getStatus()).as(semOPapel.getResponse().getContentAsString())
				.as("sessão válida sem admin: o guarda recusa a leitura").isEqualTo(403);
			assertThat(solturaSemOPapel.getResponse().getStatus())
				.as(solturaSemOPapel.getResponse().getContentAsString())
				.as("e a soltura, no mesmo caminho").isEqualTo(403);
			assertThat(estadoDaPendencia("sob_guarda")).as("nada foi solto").isEqualTo("PENDING_REMOTE");
		}
		finally {
			TestDatabase.clearAdmin(dono.userId());
		}
	}

	@Test
	void o_dry_run_mostra_a_vencida_e_nao_grava() throws Exception {
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_storage_objects");
		objetoComPendencia("sob_pendente", java.time.Instant.now().minusSeconds(3 * 3600));
		var sessao = auth.authenticate("solta_admin_seco");
		TestDatabase.setAdmin(sessao.userId());

		var resposta = mockMvc.perform(post("/admin/network/storage/pending/release")
			.header("X-Session-Token", sessao.token())).andReturn();

		assertThat(resposta.getResponse().getStatus()).as(resposta.getResponse().getContentAsString()).isEqualTo(200);
		var corpo = objectMapper.readValue(resposta.getResponse().getContentAsString(), Map.class);
		assertThat(corpo.get("dryRun")).isEqualTo(true);
		var pendentes = (List<Map<String, Object>>) corpo.get("pending");
		assertThat(pendentes).singleElement().satisfies(p -> {
			assertThat(p.get("objectId")).isEqualTo("sob_pendente");
			assertThat(p.get("key")).isEqualTo("sob_pendente.jpg");
			assertThat(p.get("expired")).isEqualTo(true);
			assertThat(p.get("nodeId")).isEqualTo("nd_perdido");
		});
		assertThat(estadoDaPendencia("sob_pendente")).isEqualTo("PENDING_REMOTE");
	}

	@Test
	void a_leitura_e_a_soltura_real_mostram_a_mesma_lista() throws Exception {
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_storage_objects");
		objetoComPendencia("sob_mesma", java.time.Instant.now().minusSeconds(3 * 3600));
		var sessao = auth.authenticate("solta_admin_real");
		TestDatabase.setAdmin(sessao.userId());

		var leitura = mockMvc.perform(get("/admin/network/storage/pending")
			.header("X-Session-Token", sessao.token())).andReturn();
		assertThat(leitura.getResponse().getStatus()).isEqualTo(200);
		var vistos = objectMapper.readValue(leitura.getResponse().getContentAsString(), Map.class);

		var real = mockMvc.perform(post("/admin/network/storage/pending/release?dryRun=false")
			.header("X-Session-Token", sessao.token())).andReturn();
		var soltos = objectMapper.readValue(real.getResponse().getContentAsString(), Map.class);

		assertThat(real.getResponse().getStatus()).as(real.getResponse().getContentAsString()).isEqualTo(200);
		assertThat(soltos.get("pending")).as("o que o operador viu é o que a rota soltou")
			.isEqualTo(vistos.get("pending"));
		assertThat(soltos.get("dryRun")).isEqualTo(false);
		assertThat(estadoDaPendencia("sob_mesma")).as("GONE: a tentativa fica, o dono da lacuna sai")
			.isEqualTo("GONE");
	}

	@Test
	void a_soltura_devolve_a_lacuna_ao_healing_sem_ligar_o_worker() throws Exception {
		// O caminho completo do operador com o auto-heal desligado (o padrão): sem a rota, a lacuna
		// ficava ALREADY_DESIGNATED para sempre, porque só o worker soltava a pendência.
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_storage_objects");
		jdbc.update("delete from network_nodes");
		objetoComPendencia("sob_retomada", java.time.Instant.now().minusSeconds(3 * 3600));
		var sessao = auth.authenticate("solta_admin_heal");
		TestDatabase.setAdmin(sessao.userId());

		var antes = mockMvc.perform(post("/admin/network/nodes/storage/heal?dryRun=true&maxObjects=50")
			.header("X-Session-Token", sessao.token())).andReturn();
		var corpoAntes = objectMapper.readValue(antes.getResponse().getContentAsString(), Map.class);
		assertThat((List<Map<String, Object>>) corpoAntes.get("skipped"))
			.extracting(p -> p.get("reason")).contains("ALREADY_DESIGNATED");

		mockMvc.perform(post("/admin/network/storage/pending/release?dryRun=false")
			.header("X-Session-Token", sessao.token())).andReturn();

		var depois = mockMvc.perform(post("/admin/network/nodes/storage/heal?dryRun=true&maxObjects=50")
			.header("X-Session-Token", sessao.token())).andReturn();
		var corpoDepois = objectMapper.readValue(depois.getResponse().getContentAsString(), Map.class);
		assertThat((List<Map<String, Object>>) corpoDepois.get("skipped"))
			.as("a lacuna voltou para a mão da cura").extracting(p -> p.get("reason"))
			.doesNotContain("ALREADY_DESIGNATED");
	}
}