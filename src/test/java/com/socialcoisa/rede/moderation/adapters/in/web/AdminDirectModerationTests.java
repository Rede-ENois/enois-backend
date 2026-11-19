package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Ações administrativas DIRETAS (E-AD-1..3): advertência, banimento e remoção de publicação por
 * ADMIN, sem votação.
 *
 * <p>Prova, em cada ação: o admin age sem quórum; o moderador aprovado NÃO age por esta porta; a
 * conta comum recebe 403; a decisão fica na auditoria; e o banimento bloqueia de verdade (sessão
 * revogada e login recusado).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminDirectModerationTests {

	private static final String REASON = "Publicações repetidas fora das diretrizes, com reincidência.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		// Moderador de teste: credibilidade mínima desligada e candidatura aprovada pelo helper.
		registry.add("rede.moderation.moderator-min-reputation", () -> "-1");
		// A automação de conteúdo pode esconder um post (quarentena) entre publicar e remover —
		// ruído real da plataforma, mas não o objeto deste teste: aqui se prova a decisão do admin.
		// Desligada nas duas leituras: o @Value do serviço e a varredura lêem a propriedade direto.
		registry.add("rede.moderation.automation.enabled", () -> "false");
		registry.add("MODERATION_AUTOMATION_ENABLED", () -> "false");
		// A deduplicação por corpo (segundos) prenderia o reenvio do MESMO pedido neste teste; aqui
		// o que se prova é a idempotência pela chave de envio, que é a proteção do painel.
		registry.add("rede.security.duplicate-post-enabled", () -> "false");
	}

	// ---------------------------------------------------------------- advertência

	@Test
	void adminAdverteSemQuorumEHistoricoFicaNoPerfil() throws Exception {
		var admin = admin("dmod_warn_admin");
		var target = new TestAuthHelper(mockMvc, objectMapper).authenticate("dmod_warn_target");

		mockMvc.perform(post("/admin/moderation/users/dmod-warn-target/warnings")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "warn-dmod-why")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.activeCount").value(1))
			.andExpect(jsonPath("$.adminId").value(admin.userId()))
			.andExpect(jsonPath("$.createdAt").isNotEmpty());

		// Repetir o MESMO pedido (a mesma chave) devolve a MESMA advertência, sem criar outra.
		mockMvc.perform(post("/admin/moderation/users/dmod-warn-target/warnings")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "warn-dmod-why")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.activeCount").value(1));

		mockMvc.perform(get("/admin/moderation/users/dmod-warn-target/warnings")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1));

		// Ficha do perfil do admin traz a contagem; a leitura pública não.
		mockMvc.perform(get("/admin/moderation/users/dmod-warn-target").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.activeWarnings").value(1));
		mockMvc.perform(get("/users/dmod-warn-target"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.activeWarnings").isEmpty());
		// Quem chama o painel com o @ em MAIÚSCULA recebe a mesma ficha, não um 404.
		mockMvc.perform(get("/admin/moderation/users/DMOD-WARN-TARGET")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("dmod-warn-target"))
			.andExpect(jsonPath("$.activeWarnings").value(1));

		// A pessoa é avisada e a decisão fica na auditoria com o motivo.
		assertThat(count("select count(*) from user_warnings where user_id = ? and revoked_at is null",
			target.userId())).isEqualTo(1);
		assertThat(one("select actor_id from audit_logs where action = 'ADMIN_WARNING_ISSUED' "
			+ "and target_id = ? order by created_at desc limit 1", target.userId())).isEqualTo(admin.userId());
		assertThat(one("select metadata from audit_logs where action = 'ADMIN_WARNING_ISSUED' "
			+ "and target_id = ? order by created_at desc limit 1", target.userId())).contains(REASON);
		assertThat(count("select count(*) from notifications where user_id = ? "
			+ "and title = 'Advertência da administração'", target.userId())).isEqualTo(1);
	}

	@Test
	void motivoCurtoNaAdvertenciaERecusado() throws Exception {
		var admin = admin("dmod_warn_admin2");
		new TestAuthHelper(mockMvc, objectMapper).authenticate("dmod_warn_target2");

		mockMvc.perform(post("/admin/moderation/users/dmod-warn-target2/warnings")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", "curto"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
	}

	@Test
	void adminNaoAdverteApropriaContaNemContaInexistente() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("dmod_warn_staff");
		TestDatabase.setAdmin(admin.userId());

		mockMvc.perform(post("/admin/moderation/users/dmod-warn-staff/warnings")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CANNOT_WARN_SELF"));

		mockMvc.perform(post("/admin/moderation/users/nao_existe_dmod/warnings")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
	}

	@Test
	void revogarAdvertenciaPreservaOHistorico() throws Exception {
		var admin = admin("dmod_revoke_admin");
		new TestAuthHelper(mockMvc, objectMapper).authenticate("dmod_revoke_target");

		MvcResult created = mockMvc.perform(post("/admin/moderation/users/dmod-revoke-target/warnings")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "warn-dmod-revoke")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isCreated())
			.andReturn();
		String warningId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(post("/admin/moderation/warnings/" + warningId + "/revoke")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("reason", "Advertência aplicada no perfil errado, revisada pela administração."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false))
			.andExpect(jsonPath("$.revokedAt").isNotEmpty());

		mockMvc.perform(get("/admin/moderation/users/dmod-revoke-target/warnings")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].active").value(false));
	}

	// ---------------------------------------------------------------- banimento direto

	@Test
	void adminBaneDiretamenteESessaoELoginSaoBloqueados() throws Exception {
		var admin = admin("dmod_ban_admin");
		var target = new TestAuthHelper(mockMvc, objectMapper).authenticate("dmod_ban_target");

		// Antes: a sessão vale.
		mockMvc.perform(get("/moderation/me").header("X-Session-Token", target.token()))
			.andExpect(status().isOk());

		mockMvc.perform(post("/admin/moderation/users/dmod-ban-target/sanction")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "ban-dmod-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "PERMABAN", "reason", REASON))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.type").value("PERMABAN"))
			.andExpect(jsonPath("$.status").value("ACTIVE"));

		// A sessão que existia morre; o login novo é recusado; a conta some da leitura pública.
		mockMvc.perform(get("/moderation/me").header("X-Session-Token", target.token()))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/auth/login")
				.header("Idempotency-Key", "login-dmod-banned")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("email", "dmod_ban_target@test.com", "password", "Test1234!"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_BANNED"));
		mockMvc.perform(get("/users/dmod-ban-target")).andExpect(status().isNotFound());

		// Histórico administrativo preservado.
		assertThat(one("select actor_id from audit_logs where action = 'ADMIN_SANCTION_APPLIED' "
			+ "and target_id = ? order by created_at desc limit 1", target.userId())).isEqualTo(admin.userId());
		mockMvc.perform(get("/admin/moderation/users/dmod-ban-target/summary").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.activeSanction.type").value("PERMABAN"))
			.andExpect(jsonPath("$.activeSanction.active").value(true));
		mockMvc.perform(get("/admin/sanctions").param("status", "ACTIVE").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk());
	}

	@Test
	void banimentoExigeJustificativa() throws Exception {
		var admin = admin("dmod_ban_admin2");
		new TestAuthHelper(mockMvc, objectMapper).authenticate("dmod_ban_target2");

		mockMvc.perform(post("/admin/moderation/users/dmod-ban-target2/sanction")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "BAN", "reason", ""))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("REASON_REQUIRED"));

		mockMvc.perform(post("/admin/moderation/users/dmod-ban-target2/sanction")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "NAO_EXISTE", "reason", REASON))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_SANCTION_TYPE"));
	}

	// ---------------------------------------------------------------- remoção de publicação

	@Test
	void adminRemovePublicacaoComMotivoSemDenuncia() throws Exception {
		var admin = admin("dmod_post_admin");
		var author = new TestAuthHelper(mockMvc, objectMapper).authenticate("dmod_post_author");
		String postId = publish(author, "Compartilhando o resumo do encontro de tecnologia desta semana com a comunidade.", "dmod-post-a1");

		// A chave da decisão é o par (administrador, remoção de UM post): retry do painel ou da rede
		// devolve a MESMA resposta sem remover de novo — o mesmo contrato das outras escritas da API.
		String body = objectMapper.writeValueAsString(Map.of("reason", REASON));
		mockMvc.perform(post("/admin/moderation/posts/" + postId + "/remove")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "remove-dmod-1-" + postId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REMOVED"))
			.andReturn().getResponse().getContentAsString();
		mockMvc.perform(post("/admin/moderation/posts/" + postId + "/remove")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "remove-dmod-1-" + postId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REMOVED"));
		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", author.token()))
			.andExpect(status().isNotFound());

		// Preservado para auditoria: remoção lógica + linha do administrador com o motivo.
		assertThat(one("select status from posts where id = ?", postId)).isEqualTo("DELETED");
		assertThat(one("select actor_id from audit_logs where action = 'ADMIN_POST_REMOVED' and target_id = ? "
			+ "order by created_at desc limit 1", postId)).isEqualTo(admin.userId());

		// Repetir: ALREADY_OFFLINE, sem segundo registro de remoção.
		mockMvc.perform(post("/admin/moderation/posts/" + postId + "/remove")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ALREADY_OFFLINE"));
		assertThat(count("select count(*) from audit_logs where action = 'ADMIN_POST_REMOVED' and target_id = ?",
			postId)).isEqualTo(1);

		mockMvc.perform(post("/admin/moderation/posts/post_inexistente_dmod/remove")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.status").value("NOT_FOUND"));
	}

	@Test
	void remocaoAdministrativaExigeMotivoEANaoVazaSucesso() throws Exception {
		var admin = admin("dmod_post_admin2");
		var author = new TestAuthHelper(mockMvc, objectMapper).authenticate("dmod_post_author2");
		String postId = publish(author, "Publicação sem motivo de remoção informado.", "dmod-post-a2");

		mockMvc.perform(post("/admin/moderation/posts/" + postId + "/remove")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", "spam"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("REASON_REQUIRED"));

		// O post continua no ar: a recusa não remove nada.
		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", author.token()))
			.andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- autorização

	@Test
	void moderadorAprovadoNaoUsaAsAcoesDiretas() throws Exception {
		var moderator = moderator("dmod_plain_mod", "dmod_plain_mod_target");
		new TestAuthHelper(mockMvc, objectMapper).authenticate("dmod_plain_mod_target");
		var author = new TestAuthHelper(mockMvc, objectMapper).authenticate("dmod_plain_mod_author");
		String postId = publish(author, "Publicação que o moderador tentaria remover direto.", "dmod-mod-post-a3");

		mockMvc.perform(post("/admin/moderation/users/dmod-plain-mod-target/warnings")
				.header("X-Session-Token", moderator.token())
				.header("Idempotency-Key", "dmod-mod-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/admin/moderation/users/dmod-plain-mod-target/sanction")
				.header("X-Session-Token", moderator.token())
				.header("Idempotency-Key", "dmod-mod-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "PERMABAN", "reason", REASON))))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/admin/moderation/posts/" + postId + "/remove")
				.header("X-Session-Token", moderator.token())
				.header("Idempotency-Key", "dmod-mod-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/moderation/users/dmod-plain-mod-target/warnings")
				.header("X-Session-Token", moderator.token()))
			.andExpect(status().isForbidden());

		// Nada foi aplicado — nem advertência, nem ban, nem remoção.
		assertThat(count("select count(*) from user_warnings where user_id in "
			+ "(select id from users where username = 'dmod-plain-mod-target')", null)).isZero();
		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", author.token()))
			.andExpect(status().isOk());

		// O caminho de votação do moderador continua aberto (não foi tocado).
		mockMvc.perform(get("/moderation/reports").header("X-Session-Token", moderator.token()))
			.andExpect(status().isOk());
	}

	@Test
	void contaComumNaoUsaNadaDaSuperficieAdministrativa() throws Exception {
		var member = new TestAuthHelper(mockMvc, objectMapper).authenticate("dmod_member");
		new TestAuthHelper(mockMvc, objectMapper).authenticate("dmod_member_target");

		for (String path : List.of("/admin/moderation/users/dmod-member-target",
				"/admin/moderation/users/dmod-member-target/warnings",
				"/admin/moderation/users/dmod-member-target/summary")) {
			mockMvc.perform(get(path).header("X-Session-Token", member.token()))
				.andExpect(status().isForbidden());
		}
		mockMvc.perform(post("/admin/moderation/users/dmod-member-target/warnings")
				.header("X-Session-Token", member.token())
				.header("Idempotency-Key", "dmod-member-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isForbidden());
		// Sem sessão: 401, não 403 — a rota exige autenticação antes de decidir papel.
		mockMvc.perform(post("/admin/moderation/users/dmod-member-target/sanction")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "PERMABAN", "reason", REASON))))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void moderadorContinuaComOVotoEANaoGanhaAcaoDireta() throws Exception {
		var helper = new TestAuthHelper(mockMvc, objectMapper);
		// A conta que o moderador sanciona/lê no fim tem de poder continuar entrando: é a conta de
		// apoio do cenário, separada do autor para que um 403 não venha do próprio alvo banido.
		var author = helper.authenticate("dmod_quorum_author");
		var support = helper.authenticate("dmod_quorum_support");
		var moderator = moderator("dmod_quorum_mod", "dmod_quorum_author");
		String postId = publish(author, "Publicação que o moderador denuncia e vota.", "dmod-quorum-post");

		// O caminho do quórum continua inteiro: denúncia, fila, voto e proposta de sanção coletiva.
		// A denúncia é da conta; quem vota é o moderador (quem denuncia não decide a própria denúncia).
		MvcResult reported = mockMvc.perform(post("/reports")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "dmod-quorum-report")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("targetType", "POST", "targetId", postId,
					"reason", "SPAM", "details", "Conteúdo repetido na rede inteira."))))
			.andExpect(status().isCreated())
			.andReturn();
		String reportId = objectMapper.readTree(reported.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(get("/moderation/reports").header("X-Session-Token", moderator.token()))
			.andExpect(status().isOk());
		mockMvc.perform(post("/moderation/reports/" + reportId + "/vote")
				.header("X-Session-Token", moderator.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("vote", "PUNISH", "note", "Fora das diretrizes."))))
			.andExpect(status().isOk());
		mockMvc.perform(get("/moderation/sanction-proposals").header("X-Session-Token", moderator.token()))
			.andExpect(status().isOk());
		// A rota antiga de sanção por moderador continua existindo (não foi removida).
		mockMvc.perform(post("/admin/users/dmod-quorum-support/sanction")
				.header("X-Session-Token", moderator.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "RESTRICTION", "reason", REASON))))
			.andExpect(status().isOk());
		// O papel não mudou e as ações diretas continuam fechadas para ele.
		mockMvc.perform(get("/moderation/me").header("X-Session-Token", moderator.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.moderator").value(true))
			.andExpect(jsonPath("$.admin").value(false));
		mockMvc.perform(get("/admin/moderation/users/dmod-quorum-support")
				.header("X-Session-Token", moderator.token()))
			.andExpect(status().isForbidden());
		for (String path : List.of("/admin/moderation/users/dmod-quorum-support/warnings",
				"/admin/moderation/users/dmod-quorum-support/summary")) {
			mockMvc.perform(get(path).header("X-Session-Token", moderator.token()))
				.andExpect(status().isForbidden());
		}
		for (String path : List.of("/admin/moderation/users/dmod-quorum-support/warnings",
				"/admin/moderation/users/dmod-quorum-support/sanction")) {
			mockMvc.perform(post(path)
					.header("X-Session-Token", moderator.token())
					.header("Idempotency-Key", "dmod-quorum-mods-" + path.hashCode())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("type", "PERMABAN", "reason", REASON))))
				.andExpect(status().isForbidden());
		}
		mockMvc.perform(post("/admin/moderation/posts/" + postId + "/remove")
				.header("X-Session-Token", moderator.token())
				.header("Idempotency-Key", "dmod-quorum-mod-remove")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isForbidden());

		// Nada do painel direto foi aplicado: a única sanção é a restrição da ROTA ANTIGA (a que o
		// moderador já tinha), nenhum PERMABAN saiu daqui, nenhuma advertência apareceu e o voto de
		// um moderador continua sem decidir sozinho (o quórum de sempre).
		assertThat(count("select count(*) from user_sanctions where user_id = ? and status = 'ACTIVE' "
			+ "and type = 'PERMABAN'", support.userId())).isZero();
		assertThat(count("select count(*) from user_warnings where user_id = ?", support.userId())).isZero();
		mockMvc.perform(get("/moderation/sanction-proposals").header("X-Session-Token", moderator.token()))
			.andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- IDOR e consistência

	@Test
	void idManipuladoNaoAgeSobreOutraContaNemOutraPublicacao() throws Exception {
		var helper = new TestAuthHelper(mockMvc, objectMapper);
		var admin = admin("dmod_idor_admin");
		var alvo = helper.authenticate("dmod_idor_alvo");
		var terceiro = helper.authenticate("dmod_idor_terceiro");
		String postDoTerceiro = publish(terceiro, "Publicação de outra conta, que ninguém mexe aqui.", "dmod-idor-post");

		// Revogar advertência de OUTRA conta pelo id (id colado de outro histórico) não cria nem
		// altera nada: o id tem de existir e a rota é de quem administra.
		var moderator = moderator("dmod_idor_mod", "dmod_idor_alvo");
		mockMvc.perform(post("/admin/moderation/warnings/warn_inexistente_nao_e_meu/revoke")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", "Revogação de teste com motivo válido."))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("WARNING_NOT_FOUND"));
		mockMvc.perform(post("/admin/moderation/warnings/warn_inexistente_nao_e_meu/revoke")
				.header("X-Session-Token", moderator.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", "Revogação de teste com motivo válido."))))
			.andExpect(status().isForbidden());

		// Advertência no id de outra conta (username trocado) atinge só a conta do caminho.
		mockMvc.perform(post("/admin/moderation/users/dmod-idor-alvo/warnings")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.userId").value(alvo.userId()));
		assertThat(count("select count(*) from user_warnings where user_id = ?", terceiro.userId())).isZero();

		// Remoção de publicação com id de outra rota (não é o post alvo) não remove o post do terceiro.
		mockMvc.perform(post("/admin/moderation/posts/post_nao_existe_idor/remove")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isNotFound());
		mockMvc.perform(get("/feed/posts/" + postDoTerceiro).header("X-Session-Token", terceiro.token()))
			.andExpect(status().isOk());

		// Banimento com username inexistente: 404 e ninguém é sancionado.
		mockMvc.perform(post("/admin/moderation/users/dmod-nao-existe-idor/sanction")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "PERMABAN", "reason", REASON))))
			.andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select count(*) from user_sanctions where user_id in (?, ?) "
			+ "and status = 'ACTIVE'", Integer.class, alvo.userId(), terceiro.userId())).isZero();
	}

	@Test
	void banimentoDiretoDerubaTodasAsSessoesECaiNoGateDeEscrita() throws Exception {
		var admin = admin("dmod_gate_admin");
		var alvo = new TestAuthHelper(mockMvc, objectMapper).authenticate("dmod_gate_alvo");

		mockMvc.perform(post("/admin/moderation/users/dmod-gate-alvo/sanction")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "BAN", "reason", REASON))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));

		// O BAN é permanente no banco (não fica como "restrição") e a sessão já não serve.
		assertThat(one("select type from user_sanctions where user_id = ? and status = 'ACTIVE'", alvo.userId()))
			.isEqualTo("BAN");
		mockMvc.perform(get("/auth/me").header("X-Session-Token", alvo.token()))
			.andExpect(status().isUnauthorized());
		// Nenhum token de sessão ativo sobra para a conta.
		assertThat(count("select count(*) from auth_sessions where user_id = ? and revoked_at is null", alvo.userId()))
			.isZero();
	}

	@Test
	void pararDeAdvertirPreservaAMensagemERevogacaoExigeMotivo() throws Exception {
		var admin = admin("dmod_rev2_admin");
		new TestAuthHelper(mockMvc, objectMapper).authenticate("dmod_rev2_alvo");
		MvcResult criada = mockMvc.perform(post("/admin/moderation/users/dmod-rev2-alvo/warnings")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "warn-rev2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isCreated())
			.andReturn();
		String warningId = objectMapper.readTree(criada.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(post("/admin/moderation/warnings/" + warningId + "/revoke")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", "curto"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
		// Revogar duas vezes é 409, não um segundo histórico.
		mockMvc.perform(post("/admin/moderation/warnings/" + warningId + "/revoke")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", "Revisão completa do caso pela administração."))))
			.andExpect(status().isOk());
		mockMvc.perform(post("/admin/moderation/warnings/" + warningId + "/revoke")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", "Revisão completa do caso pela administração."))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WARNING_ALREADY_REVOKED"));
	}

	@Test
	void historicoAdministrativoReuneAdvertenciasESancoes() throws Exception {
		var admin = admin("dmod_hist_admin");
		var alvo = new TestAuthHelper(mockMvc, objectMapper).authenticate("dmod_hist_alvo");

		mockMvc.perform(post("/admin/moderation/users/dmod-hist-alvo/warnings")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isCreated());
		mockMvc.perform(post("/admin/moderation/users/dmod-hist-alvo/sanction")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "RESTRICTION", "reason", REASON))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/admin/moderation/users/dmod-hist-alvo/history")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("dmod-hist-alvo"))
			.andExpect(jsonPath("$.rows.length()").value(2))
			.andExpect(jsonPath("$.rows[?(@.kind == 'WARNING')].reason").value(org.hamcrest.Matchers.hasItem(REASON)))
			.andExpect(jsonPath("$.rows[?(@.kind == 'SANCTION')].type").value(org.hamcrest.Matchers.hasItem("RESTRICTION")));

		// O histórico da conta é dado de moderação: conta comum e moderador não leem.
		var member = new TestAuthHelper(mockMvc, objectMapper).authenticate("dmod_hist_member");
		mockMvc.perform(get("/admin/moderation/users/dmod-hist-alvo/history")
				.header("X-Session-Token", member.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/moderation/users/dmod-hist-alvo/history")
				.header("X-Session-Token", admin.token()))
			.andExpect(jsonPath("$.userId").value(alvo.userId()));
	}

	// ---------------------------------------------------------------- helpers

	private TestAuthHelper.AuthSession admin(String name) throws Exception {
		var helper = new TestAuthHelper(mockMvc, objectMapper);
		var session = helper.authenticate(name);
		// A coluna `users.is_admin` é a fonte do papel; o helper HTTP só promove um e-mail fixo.
		TestDatabase.setAdmin(session.userId());
		return session;
	}

	/** Moderador aprovado de verdade: candidatura + aprovação por um administrador. */
	private TestAuthHelper.AuthSession moderator(String name, String target) throws Exception {
		var helper = new TestAuthHelper(mockMvc, objectMapper);
		var session = helper.authenticate(name);
		var admin = admin(name + "_boss");
		if (count("select count(*) from moderator_applications where user_id = ? and status = 'PENDING'",
				session.userId()) == 0
				&& count("select count(*) from moderator_applications where user_id = ? and status = 'APPROVED'",
					session.userId()) == 0) {
			mockMvc.perform(post("/moderation/applications")
					.header("X-Session-Token", session.token())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of(
						"motivation", "Quero ajudar a manter a comunidade segura e acolhedora para todas as pessoas.",
						"experience", "Já moderei comunidades.",
						"availability", "Todas as noites."))))
				.andExpect(status().is2xxSuccessful());
		}
		List<Map<String, Object>> pending = jdbc.queryForList("select id from moderator_applications "
			+ "where user_id = ? and status = 'PENDING'", session.userId());
		for (Map<String, Object> row : pending) {
			mockMvc.perform(post("/admin/moderator-applications/" + row.get("id") + "/approve")
					.header("X-Session-Token", admin.token())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("note", "Aprovado no teste."))))
				.andExpect(status().isOk());
		}
		return session;
	}

	private String publish(TestAuthHelper.AuthSession session, String content, String idem) throws Exception {
		MvcResult created = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", idem)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", content, "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	private int count(String sql, Object arg) {
		Integer value = arg == null ? jdbc.queryForObject(sql, Integer.class)
			: jdbc.queryForObject(sql, Integer.class, arg);
		return value == null ? 0 : value;
	}

	private String one(String sql, Object arg) {
		List<String> rows = jdbc.queryForList(sql, String.class, arg);
		return rows.isEmpty() ? null : rows.getFirst();
	}
}