package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Modelos de processo seletivo (biblioteca pessoal aplicada por cópia) e ciclo de vida da vaga:
 * reabrir encerrada, prazo de candidaturas e encerrar pela equipe da página.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProcessLifecycleTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	/** Mesmos overrides dos outros fluxos de vaga, para compartilhar um contexto de teste só. */
	@MockitoBean
	private ReputationService reputationService;

	@MockitoBean
	private EmailService emailService;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		when(reputationService.reputationOf(anyString())).thenReturn(300);
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void templateAppliesByCopyAndLifecycleReopensAndEnforcesDeadline() throws Exception {
		var owner = auth.authenticate("plc_owner");
		var candidate = auth.authenticate("plc_candidate");
		String uid = UUID.randomUUID().toString().substring(0, 8);

		// Biblioteca pessoal: criar modelo, listar e recusar nome repetido.
		String templateId = json(mockMvc.perform(post("/jobs/recruitment/templates")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("name", "Triagem " + uid, "stages", java.util.List.of("Triagem", "Entrevista técnica")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stages[0]").value("Triagem"))
			.andReturn()).get("id").asText();
		mockMvc.perform(post("/jobs/recruitment/templates")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("name", "triagem " + uid, "stages", java.util.List.of("Única")))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TEMPLATE_NAME_TAKEN"));
		mockMvc.perform(get("/jobs/recruitment/templates").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + templateId + "')].name").value("Triagem " + uid));
		// Modelo sem etapa é recusa explícita.
		mockMvc.perform(post("/jobs/recruitment/templates")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Vazio " + uid, "stages",
					java.util.List.of()))))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("TEMPLATE_STAGES_REQUIRED"));

		// Modelo é pessoal: outra pessoa não vê nem pode apagar.
		var stranger = auth.authenticate("plc_stranger");
		mockMvc.perform(delete("/jobs/recruitment/templates/" + templateId)
				.header("X-Session-Token", stranger.token()))
			.andExpect(status().isForbidden());

		// Vaga criada com prazo no futuro.
		String jobId = json(mockMvc.perform(post("/jobs")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(jobBody("Vaga com modelo " + uid,
					java.time.Instant.now().plusSeconds(3600).toString())))
			.andExpect(status().isCreated())
			.andReturn()).get("id").asText();

		// Aplicar o modelo: etapas nascem na ordem do modelo, e aplicar de novo é 409.
		mockMvc.perform(post("/jobs/" + jobId + "/process/templates/apply")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("templateId", templateId))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].name").value("Triagem"))
			.andExpect(jsonPath("$[1].name").value("Entrevista técnica"));
		mockMvc.perform(post("/jobs/" + jobId + "/process/templates/apply")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("templateId", templateId))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PIPELINE_ALREADY_CONFIGURED"));

		// Prazo no passado é recusa do domínio (422), tanto na criação quanto no ajuste.
		mockMvc.perform(put("/jobs/" + jobId + "/deadline")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("deadline", java.time.Instant.now().minusSeconds(60).toString()))))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("INVALID_DEADLINE"));

		// Vaga encerrada não recebe candidatura; reaberta volta a receber, preservando o fluxo.
		mockMvc.perform(post("/jobs/" + jobId + "/close").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CLOSED"));
		mockMvc.perform(apply(candidate, jobId))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("JOB_CLOSED"));
		mockMvc.perform(post("/jobs/" + jobId + "/reopen").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("OPEN"))
			.andExpect(jsonPath("$.closedAt").doesNotExist());
		// Reabrir vaga aberta é erro de estado, não sucesso silencioso.
		mockMvc.perform(post("/jobs/" + jobId + "/reopen").header("X-Session-Token", owner.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("JOB_NOT_CLOSED"));
		// Terceiro não reabre nem ajusta prazo da vaga alheia.
		mockMvc.perform(post("/jobs/" + jobId + "/reopen").header("X-Session-Token", stranger.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));
		// O fluxo aplicado sobreviveu ao fechar/reabrir.
		mockMvc.perform(get("/jobs/" + jobId + "/process/stages").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2));

		// Prazo aproximado: recusa explícita com código próprio (diferente de JOB_CLOSED).
		mockMvc.perform(put("/jobs/" + jobId + "/deadline")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("deadline", java.time.Instant.now().plusSeconds(2).toString()))))
			.andExpect(status().isOk());
		Thread.sleep(2100);
		mockMvc.perform(apply(candidate, jobId))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPLICATION_DEADLINE_PASSED"));

		// Dono apaga o modelo da biblioteca; buscar de novo é 404.
		mockMvc.perform(delete("/jobs/recruitment/templates/" + templateId)
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());
		mockMvc.perform(delete("/jobs/recruitment/templates/" + templateId)
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isNotFound());
	}

	@Test
	void pageTeamClosesAndReopensTheJobAndDeadlineChangeIsAuthorOnly() throws Exception {
		var owner = auth.authenticate("plc_page_owner");
		var recruiter = auth.authenticate("plc_page_recruiter");
		var candidate = auth.authenticate("plc_page_candidate");
		String uid = UUID.randomUUID().toString().substring(0, 8);
		JsonNode page = json(mockMvc.perform(post("/empresas")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Page Lifecycle " + uid))))
			.andExpect(status().isOk())
			.andReturn());
		String companyId = page.get("id").asText();
		// Papel de página (RECRUITER) para publicar + papel de recrutamento para conduzir.
		mockMvc.perform(post("/empresas/" + companyId + "/members")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", recruiter.userId(), "role", "RECRUITER"))))
			.andExpect(status().isOk());
		String jobId = json(mockMvc.perform(post("/jobs")
				.header("X-Session-Token", recruiter.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(jobBody("Vaga da página " + uid, null)
					.replace("\"skills\":[\"Java\"]", "\"skills\":[\"Java\"],\"companyId\":\"" + companyId + "\"")))
			.andExpect(status().isCreated())
			.andReturn()).get("id").asText();

		// Quem conduz a seleção na página encerra e reabre — não só o autor.
		mockMvc.perform(post("/jobs/" + jobId + "/close").header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CLOSED"));
		mockMvc.perform(post("/jobs/" + jobId + "/reopen").header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("OPEN"));

		// Ajuste de prazo na vaga da página pede CONFIGURE_PROCESS: o candidato de fora recebe
		// a mesma recusa de sempre (NOT_OWNER), sem vazar que a vaga tem equipe.
		mockMvc.perform(put("/jobs/" + jobId + "/deadline")
				.header("X-Session-Token", candidate.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("deadline", "2030-01-01T00:00:00Z"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));
	}

	private org.springframework.test.web.servlet.RequestBuilder apply(TestAuthHelper.AuthSession who,
			String jobId) {
		return post("/jobs/" + jobId + "/applications")
			.header("X-Session-Token", who.token())
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"message\":\"Tenho interesse.\"}");
	}

	private static String jobBody(String title, String deadline) {
		return """
				{"title":"%s","company":"Acme","location":"Remoto","type":"CLT","level":"PLENO",
				 "currency":"BRL","salaryMin":"1500","salaryPeriod":"MONTH","description":"Vaga de teste com valor.",
				 "skills":["Java"],"communityAgreement":true,"applicationFields":[]%s}"""
			.formatted(title, deadline == null ? "" : ",\"applicationDeadline\":\"" + deadline + "\"");
	}

	private JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}
}
