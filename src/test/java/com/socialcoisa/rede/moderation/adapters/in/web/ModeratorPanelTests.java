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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Candidatura a moderador -> aprovacao por admin -> painel -> votacao com quorum -> punicao.
 * Tambem cobre a punicao direta de qualquer usuario por um admin do painel.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModeratorPanelTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * minReputation = -1 para que o usuario recem-criado (reputacao 0) seja elegivel; quorum 2 para
	 * a votacao fechar com o moderador + o admin. O limiar real (500) tem teste proprio em
	 * ModeratorEligibilityTests.
	 */
	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "mp_admin@test.com");
		registry.add("rede.moderation.moderator-min-reputation", () -> "-1");
		registry.add("rede.moderation.vote-quorum", () -> "2");
	}

	@Test
	void applicationApprovalVotingAndDirectSanctionFlow() throws Exception {
		var author = new TestAuthHelper(mockMvc, objectMapper).authenticate("mp_author");
		var reporter = new TestAuthHelper(mockMvc, objectMapper).authenticate("mp_reporter");
		var applicant = new TestAuthHelper(mockMvc, objectMapper).authenticate("mp_mod");
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("mp_admin");

		// Estado inicial: elegivel, ainda sem papel.
		mockMvc.perform(get("/moderation/me").header("X-Session-Token", applicant.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.eligible").value(true))
			.andExpect(jsonPath("$.moderator").value(false))
			.andExpect(jsonPath("$.admin").value(false));

		// Antes de aprovado, o painel de denuncias e negado.
		mockMvc.perform(get("/moderation/reports").header("X-Session-Token", applicant.token()))
			.andExpect(status().isForbidden());

		// Formulario de candidatura.
		MvcResult applied = mockMvc.perform(post("/moderation/applications")
				.header("X-Session-Token", applicant.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"motivation", "Quero ajudar a comunidade a manter as conversas saudaveis e justas.",
					"experience", "Participo das comunidades desde o comeco.",
					"availability", "Algumas horas por semana."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn();
		String applicationId = objectMapper.readTree(applied.getResponse().getContentAsString())
			.get("id").asText();

		// Candidatura duplicada enquanto pendente e recusada.
		mockMvc.perform(post("/moderation/applications")
				.header("X-Session-Token", applicant.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"motivation", "Uma segunda candidatura enquanto a primeira ainda esta na fila."))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPLICATION_PENDING"));

		// Nao-admin nao enxerga a fila de candidaturas.
		mockMvc.perform(get("/admin/moderator-applications").header("X-Session-Token", applicant.token()))
			.andExpect(status().isForbidden());

		// Admin ve e aprova.
		mockMvc.perform(get("/admin/moderator-applications").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + applicationId + "')].status").value("PENDING"));

		mockMvc.perform(post("/admin/moderator-applications/" + applicationId + "/approve")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("note", "Bem-vindo ao time."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"));

		mockMvc.perform(get("/moderation/me").header("X-Session-Token", applicant.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.moderator").value(true));

		// Um post e uma denuncia para colocar no painel.
		String postId = publish(author, "Conteudo que a moderacao vai analisar.", "mp-post");
		String reportId = report(reporter, postId);

		mockMvc.perform(get("/moderation/reports").header("X-Session-Token", applicant.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.reportId == '" + reportId + "')].punishVotes").value(0))
			.andExpect(jsonPath("$[?(@.reportId == '" + reportId + "')].quorum").value(2));

		// Primeiro voto: ainda sem quorum.
		mockMvc.perform(post("/moderation/reports/" + reportId + "/vote")
				.header("X-Session-Token", applicant.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("vote", "PUNISH", "note", "Ofende a comunidade."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.punishVotes").value(1))
			.andExpect(jsonPath("$.decision").value("PENDING"));

		// O denunciante nao vota na propria denuncia.
		mockMvc.perform(post("/moderation/reports/" + reportId + "/vote")
				.header("X-Session-Token", reporter.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("vote", "PUNISH"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		// Admin tambem vota; segundo voto fecha o quorum de punicao.
		mockMvc.perform(post("/moderation/reports/" + reportId + "/vote")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("vote", "PUNISH"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.punishVotes").value(2))
			.andExpect(jsonPath("$.decision").value("PENALIZED"))
			.andExpect(jsonPath("$.reportStatus").value("AUTO_PENALIZED"));

		// A punicao do fluxo existente restringe o autor: nao publica mais.
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "mp-blocked")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "nao deveria passar", "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));

		// PUNICAO DIRETA: admin restringe qualquer usuario pelo username, sem denuncia.
		var victim = new TestAuthHelper(mockMvc, objectMapper).authenticate("mp_victim");
		mockMvc.perform(post("/admin/users/mp-victim/sanction")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "RESTRICTION", "reason", "Descumpriu as diretrizes em conversa privada."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("mp-victim"))
			.andExpect(jsonPath("$.type").value("RESTRICTION"))
			.andExpect(jsonPath("$.status").value("ACTIVE"));

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", victim.token())
				.header("Idempotency-Key", "mp-victim-blocked")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "vitima nao publica", "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));

		// A sessao do admin nao pode ser punida por ele mesmo.
		mockMvc.perform(post("/admin/users/mp-admin/sanction")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "BAN", "reason", "teste de autopunicao"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CANNOT_SANCTION_SELF"));
	}

	@Test
	void moderatorCanChangeVoteWhileTheReportIsPending() throws Exception {
		var author = new TestAuthHelper(mockMvc, objectMapper).authenticate("mp_author2");
		var reporter = new TestAuthHelper(mockMvc, objectMapper).authenticate("mp_reporter2");
		var applicant = new TestAuthHelper(mockMvc, objectMapper).authenticate("mp_mod2");
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("mp_admin");

		String applicationId = apply(applicant, "Quero moderar para manter o feed seguro e acolhedor.");
		mockMvc.perform(post("/admin/moderator-applications/" + applicationId + "/approve")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("note", "ok"))))
			.andExpect(status().isOk());

		String postId = publish(author, "Post para o segundo fluxo de voto.", "mp2-post");
		String reportId = report(reporter, postId);

		// Vota PUNISH e depois troca para DISMISS: continua um voto so.
		mockMvc.perform(post("/moderation/reports/" + reportId + "/vote")
				.header("X-Session-Token", applicant.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("vote", "PUNISH"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.punishVotes").value(1));

		mockMvc.perform(post("/moderation/reports/" + reportId + "/vote")
				.header("X-Session-Token", applicant.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("vote", "DISMISS", "note", "Revi e nao vejo problema."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.punishVotes").value(0))
			.andExpect(jsonPath("$.dismissVotes").value(1))
			.andExpect(jsonPath("$.myVote").value("DISMISS"));
	}

	@Test
	void moderatorReviewsGameSubmission() throws Exception {
		var dev = new TestAuthHelper(mockMvc, objectMapper).authenticate("mp_gamedev");
		var applicant = new TestAuthHelper(mockMvc, objectMapper).authenticate("mp_mod3");
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("mp_admin");

		String applicationId = apply(applicant, "Quero revisar jogos da comunidade com cuidado.");
		mockMvc.perform(post("/admin/moderator-applications/" + applicationId + "/approve")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("note", "ok"))))
			.andExpect(status().isOk());

		// Jogo enviado pelo criador entra na fila (SUBMITTED).
		MvcResult submitted = mockMvc.perform(post("/games")
				.header("X-Session-Token", dev.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"slug", "mp-jogo",
					"title", "Jogo de teste",
					"description", "Um jogo simples para a fila de revisão.",
					"cover", "",
					"url", "https://example.com/jogo",
					"tags", "puzzle"))))
			.andExpect(status().isOk())
			.andReturn();
		String gameId = objectMapper.readTree(submitted.getResponse().getContentAsString()).get("id").asText();

		// O moderador vê a fila e aprova pelo painel.
		mockMvc.perform(get("/moderation/games").header("X-Session-Token", applicant.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + gameId + "')].title").value("Jogo de teste"));

		mockMvc.perform(post("/moderation/games/" + gameId + "/approve")
				.header("X-Session-Token", applicant.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PUBLISHED"));

		// Um usuário comum não acessa a fila do painel.
		mockMvc.perform(get("/moderation/games").header("X-Session-Token", dev.token()))
			.andExpect(status().isForbidden());
	}

	private String apply(TestAuthHelper.AuthSession session, String motivation) throws Exception {
		MvcResult result = mockMvc.perform(post("/moderation/applications")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("motivation", motivation))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String publish(TestAuthHelper.AuthSession session, String content, String idem) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", idem)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", content, "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String report(TestAuthHelper.AuthSession viewer, String postId) throws Exception {
		MvcResult result = mockMvc.perform(post("/reports")
				.header("X-Session-Token", viewer.token())
				.header("Idempotency-Key", "mp-report-" + postId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"targetType", "POST", "targetId", postId,
					"reason", "SCAM", "description", "Parece golpe"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
