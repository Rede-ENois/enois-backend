package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

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

import tools.jackson.databind.ObjectMapper;

/**
 * Conteúdo anônimo denunciado continua punível: o anonimato é do contrato público, não um
 * apagamento do vínculo autor-conteúdo. Sem a autoria, penalizar caía em {@code 409
 * CANNOT_PENALIZE} e a denúncia de desabafo ou confissão não tinha como responsabilizar ninguém.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AnonymousTargetSanctionTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@DynamicPropertySource
	static void adminAllowlist(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "anon_admin@test.com");
	}

	@Test
	void penalizarDesabafoEConfissaoRestringeOAutorEscondido() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		// Mesma conta, dois textos anônimos: as duas punições têm de cair nela.
		var desabafo = auth.authenticate("anon_desabafo");
		var confissao = desabafo;
		var denunciante = auth.authenticate("anon_denunciante");
		var admin = auth.authenticate("anon_admin");

		String desabafoId = publish("/desabafos/posts", desabafo, "desabafo com o que a regra proibe");
		String confissaoId = publish("/confessionario/posts", confissao, "confissao com o que a regra proibe");

		String reportDesabafo = report(denunciante, "DESABAFO_POST", desabafoId);
		String reportConfissao = report(denunciante, "CONFESSIONARIO_POST", confissaoId);

		mockMvc.perform(post("/admin/reports/" + reportDesabafo + "/penalize")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk());
		mockMvc.perform(post("/admin/reports/" + reportConfissao + "/penalize")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk());

		// A sanção recai sobre o autor que o contrato público não mostra.
		assertThat(activeRestrictions(desabafo.userId())).isEqualTo(1);
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", desabafo.token())
				.header("Idempotency-Key", "anon-blocked-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT",
					"content", "nao deveria passar", "tags", java.util.List.of(), "mediaIds",
					java.util.List.of()))))
			.andExpect(status().isForbidden());
	}

	@Test
	void punicaoDizAPrazoAJustificativaEOTrechoDenunciado() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var autor = auth.authenticate("prova_autor");
		var denunciante = auth.authenticate("prova_denunciante");
		var admin = auth.authenticate("anon_admin");

		String texto = "texto que a regra proibe e vai citado na punicao";
		String desabafoId = publish("/desabafos/posts", autor, texto);
		String reportId = report(denunciante, "DESABAFO_POST", desabafoId);

		mockMvc.perform(post("/admin/reports/" + reportId + "/penalize")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk());

		// A conta punida lê o motivo, a justificativa da denúncia e o trecho que a motivou — e o
		// post já saiu do ar, então a notificação é a única prova que sobra.
		assertThat(lastBody(autor)).contains("Justificativa: o conteúdo foi considerado discriminação")
			.contains("ofensa direcionada")
			.contains("Conteudo denunciado: \"" + texto + "\"")
			.contains("retirar a punição");
		assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and preview = ?",
			Long.class, autor.userId(), texto)).isEqualTo(1);
	}

	@Test
	void banimentoDiretoCitaOMotivoDoPainelEmVezDoCodigoDaDenuncia() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		// Nome único por execução: o banco é compartilhado entre as classes e o username de uma conta
		// que já existiu ganha sufixo (`-1`, `-2`...), então o da rota tem de ser o slug deste userId.
		String login = "prova_banido_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		var alvo = auth.authenticate(login);
		var admin = auth.authenticate("anon_admin");

		mockMvc.perform(post("/admin/moderation/users/" + login.replace('_', '-') + "/sanction")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "BAN",
					"reason", "divulgou conteudo sexual explicito"))))
			.andExpect(status().isOk());

		// O banimento direto do painel agora avisa a conta — antes ele era silencioso —, com o motivo
		// escrito pelo administrador como justificativa; o código interno da sanção ("admin:...") não
		// vai no texto, e não há denúncia por trás para citar.
		long avisos = jdbc.queryForObject(
			"select count(*) from notifications where user_id = ? and kind = 'SANCAO'", Long.class, alvo.userId());
		assertThat(avisos).as("o banimento avisa a conta").isEqualTo(1);
		String corpo = lastBody(alvo);
		// `BAN` é sem recurso (é o que o tipo quer dizer): a notificação diz que a conta pode ser
		// retirada do banimento por um administrador, e não convida a recorrer de algo definitivo.
		assertThat(corpo).contains("banida").contains("Justificativa: divulgou conteudo sexual explicito")
			.contains("pode ser retirada").doesNotContain("admin:", "denúncia", "denuncia")
			.doesNotContain("Conteudo denunciado");
	}

	@Test
	void provaLongaEDenunciaDeChamadaNaoEstouramONotificacao() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var autor = auth.authenticate("prova_longa");
		var denunciante = auth.authenticate("prova_longa_viewer");
		var admin = auth.authenticate("anon_admin");

		String texto = "texto longo que abre a prova " + "x".repeat(3_500);
		String confissaoId = publish("/confessionario/posts", autor, texto);
		mockMvc.perform(post("/admin/reports/" + report(denunciante, "CONFESSIONARIO_POST", confissaoId) + "/penalize")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk());

		String corpo = lastBody(autor);
		assertThat(corpo).contains("texto longo que abre a prova").hasSizeLessThanOrEqualTo(2_000);
		assertThat(jdbc.queryForObject("select char_length(preview) from notifications where user_id = ?",
			Integer.class, autor.userId())).isLessThanOrEqualTo(300);
	}

	private String lastBody(TestAuthHelper.AuthSession session) {
		return jdbc.queryForObject(
			"select body from notifications where user_id = ? order by created_at desc limit 1",
			String.class, session.userId());
	}

	private long activeRestrictions(String userId) {
		return jdbc.queryForObject(
			"select count(*) from user_sanctions where user_id = ? and type = 'RESTRICTION' and status = 'ACTIVE'",
			Long.class, userId);
	}

	private String publish(String path, TestAuthHelper.AuthSession author, String content) throws Exception {
		MvcResult created = mockMvc.perform(post(path)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "anon-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", content))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	private String report(TestAuthHelper.AuthSession viewer, String targetType, String targetId) throws Exception {
		MvcResult result = mockMvc.perform(post("/reports")
				.header("X-Session-Token", viewer.token())
				.header("Idempotency-Key", "anon-report-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"targetType", targetType, "targetId", targetId,
					"reason", "DISCRIMINATION", "description", "ofensa direcionada"))))
			.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}