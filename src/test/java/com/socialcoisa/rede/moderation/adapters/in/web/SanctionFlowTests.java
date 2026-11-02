package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.moderation.application.AiVerdictApplicationService;
import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * O fluxo inteiro que a Mel pediu: denuncia -> IA valida (veredito PENALIZAR) -> penaliza e avisa
 * -> usuario recorre -> humano aprova (libera) OU nega (ban com sessoes revogadas e login travado).
 * A IA nunca bana: ban so por recurso negado.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SanctionFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ReportRepository reports;

	@Autowired
	private AiVerdictApplicationService verdictApplication;

	@DynamicPropertySource
	static void adminAllowlist(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "sflow_admin@test.com");
	}

	@Test
	void penalizeNotifyAppealApproveFreesTheAuthor() throws Exception {
		var author = new TestAuthHelper(mockMvc, objectMapper).authenticate("sflow_author");
		var viewer = new TestAuthHelper(mockMvc, objectMapper).authenticate("sflow_viewer");
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("sflow_admin");

		String postId = publish(author, "Post que vai ser penalizado automaticamente.", "sflow-pen-pub");
		String reportId = report(viewer, postId);

		// Veredito PENALIZAR da pre-triagem (grave, inequivoco, confianca 0.95): passa no gate.
		attachVerdict(reportId, """
			{"categoria":"discurso_de_odio","gravidade":"HIGH","confianca":0.95,"ambiguo":false,
			 "acao":"PENALIZAR","justificativa":"Ofensa direcionada e discriminacao contra grupo."}
			""");
		assertThatApplied(verdictApplication.applyPendingVerdicts(10));

		// Autor restrito: nao publica nem comenta.
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "sflow-blocked-post")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "nao deveria passar", "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));

		// Acompanhamento do denunciante: status proprio da penalidade automatica.
		mockMvc.perform(get("/reports/mine").header("X-Session-Token", viewer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + reportId + "')].status").value("AUTO_PENALIZED"));

		// Autor recorre; segundo recurso em sequencia e recusado.
		MvcResult appeal = mockMvc.perform(post("/me/sanctions/appeals")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"message", "Foi discussao tecnica, nao discriminacao. Podem revisar."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn();
		String appealId = objectMapper.readTree(appeal.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(post("/me/sanctions/appeals")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "outro recurso para a mesma sancao"))))
			.andExpect(status().isConflict());

		// Fila do admin enxerga o recurso; humano APROVA: restricao sai e o autor publica de novo.
		mockMvc.perform(get("/admin/appeals")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + appealId + "')].status").value("PENDING"));

		mockMvc.perform(post("/admin/appeals/" + appealId + "/approve")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sanction.status").value("REMOVED"));

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "sflow-freed-post")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "livre de novo", "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated());
	}

	@Test
	void deniedAppealBansTheAuthorAndRevokesSessions() throws Exception {
		var author = new TestAuthHelper(mockMvc, objectMapper).authenticate("sflow_author2");
		var viewer = new TestAuthHelper(mockMvc, objectMapper).authenticate("sflow_viewer2");
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("sflow_admin");

		String postId = publish(author, "Post do segundo fluxo, recurso sera negado.", "sflow-ban-pub");
		String reportId = report(viewer, postId);

		attachVerdict(reportId, """
			{"categoria":"ameaca","gravidade":"CRITICAL","confianca":0.97,"ambiguo":false,
			 "acao":"PENALIZAR","justificativa":"Ameaca direta e inequivoca contra pessoa."}
			""");
		assertThatApplied(verdictApplication.applyPendingVerdicts(10));

		MvcResult appeal = mockMvc.perform(post("/me/sanctions/appeals")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "nao foi ameaca, foi figure de lingua"))))
			.andExpect(status().isOk()).andReturn();
		String appealId = objectMapper.readTree(appeal.getResponse().getContentAsString()).get("id").asText();

		// Humano NEGA: restricao vira BAN e as sessoes caem.
		mockMvc.perform(post("/admin/appeals/" + appealId + "/deny")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sanction.type").value("BAN"))
			.andExpect(jsonPath("$.sanction.status").value("ACTIVE"));

		// Sessao antiga morreu...
		mockMvc.perform(get("/me/sanctions").header("X-Session-Token", author.token()))
			.andExpect(status().isUnauthorized());

		// ...e o login nao sai mais (nem com a senha certa).
		mockMvc.perform(post("/auth/login")
				.header("Idempotency-Key", "sflow-banned-login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"email", "sflow_author2@test.com", "password", "Test1234!"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_BANNED"));
	}

	@Test
	void ambiguousOrLowConfidenceVerdictDoesNotPenalize() throws Exception {
		var author = new TestAuthHelper(mockMvc, objectMapper).authenticate("sflow_author3");
		var viewer = new TestAuthHelper(mockMvc, objectMapper).authenticate("sflow_viewer3");

		String postId = publish(author, "Post do fluxo de veredito ambiguo.", "sflow-amb-pub");
		String reportId = report(viewer, postId);

		// CONFIA BAIXA: o gate recusa, a denuncia segue para revisao humana e ninguem e restrito.
		attachVerdict(reportId, """
			{"categoria":"golpe","gravidade":"HIGH","confianca":0.6,"ambiguo":true,
			 "acao":"PENALIZAR","justificativa":"parece golpe mas o contexto nao fecha"}
			""");
		verdictApplication.applyPendingVerdicts(10);

		mockMvc.perform(get("/reports/mine").header("X-Session-Token", viewer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + reportId + "')].status").value("PENDING"));

		// Autor continua livre para publicar.
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "sflow-amb-free")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "ainda publicando", "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated());
	}

	@Test
	void confirmedVerdictPenalizesWithoutHighSeverity() throws Exception {
		var author = new TestAuthHelper(mockMvc, objectMapper).authenticate("sflow_author4");
		var viewer = new TestAuthHelper(mockMvc, objectMapper).authenticate("sflow_viewer4");

		String postId = publish(author, "Post do fluxo de veredito confirmado.", "sflow-conf-pub");
		String reportId = report(viewer, postId);

		// UMA denuncia comprovada, sem gravidade alta e sem tolerancia zero: penaliza assim mesmo.
		attachVerdict(reportId, """
			{"categoria":"spam","gravidade":"MEDIUM","confianca":0.8,"ambiguo":false,
			 "acao":"PENALIZAR","justificativa":"Spam confirmado no conteudo"}
			""");
		assertThatApplied(verdictApplication.applyPendingVerdicts(10));

		mockMvc.perform(get("/reports/mine").header("X-Session-Token", viewer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + reportId + "')].status").value("AUTO_PENALIZED"));
	}

	// --- helpers ---

	private void assertThatApplied(int applied) {
		org.assertj.core.api.Assertions.assertThat(applied).isGreaterThanOrEqualTo(1);
	}

	private void attachVerdict(String reportId, String verdictJson) {
		com.socialcoisa.rede.moderation.domain.Report report = reports.findById(reportId)
			.orElseThrow(() -> new IllegalStateException("denuncia nao encontrada: " + reportId));
		report.attachAiTriage(verdictJson.replace("\n", " "), Instant.now());
		reports.save(report);
	}

	private String publish(TestAuthHelper.AuthSession session, String content, String idem) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", idem)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", content, "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String report(TestAuthHelper.AuthSession viewer, String postId) throws Exception {
		MvcResult result = mockMvc.perform(post("/reports")
				.header("X-Session-Token", viewer.token())
				.header("Idempotency-Key", "sflow-report-" + postId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"targetType", "POST", "targetId", postId,
					"reason", "DISCRIMINATION", "description", "ofensa direcionada"))))
			.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
