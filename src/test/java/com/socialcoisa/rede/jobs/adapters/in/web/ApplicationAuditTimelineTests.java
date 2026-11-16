package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Timeline de auditoria do recrutamento: mover, anotar e avaliar deixam linha na trilha única do
 * sistema (a mesma do painel), com a candidatura como alvo — e a leitura é só de quem vê
 * candidatos. O candidato não vê a auditoria da equipe; o histórico dele são os movimentos.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationAuditTimelineTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private ReputationService reputationService;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		when(reputationService.reputationOf(anyString())).thenReturn(300);
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void atosDeRecrutamentoFicamNaTrilhaELeituraExigeVerCandidatos() throws Exception {
		var employer = auth.authenticate("audit_owner");
		var candidate = auth.authenticate("audit_candidate");
		auth.activateProfessional(candidate);
		String companyId = createPage(employer, "Página de auditoria");
		String jobId = createJob(employer, companyId, "audit-vaga-1");
		String applicationId = apply(jobId, candidate);

		// Nota, avaliação e movimento: três linhas na trilha, mais nova primeiro.
		mockMvc.perform(post("/jobs/" + jobId + "/process/applications/" + applicationId + "/notes")
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\":\"Primeira impressão boa.\"}"))
			.andExpect(status().isOk());
		mockMvc
			.perform(put("/jobs/" + jobId + "/process/applications/" + applicationId + "/evaluations")
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"criteria\":{\"Técnica\":7},\"verdict\":\"YES\"}"))
			.andExpect(status().isOk());
		mockMvc.perform(post("/jobs/" + jobId + "/process/stages")
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Triagem\"}"))
			.andExpect(status().isOk());
		MvcResult stage = mockMvc.perform(post("/jobs/" + jobId + "/process/stages")
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Entrevista\"}"))
			.andExpect(status().isOk())
			.andReturn();
		String stageId = objectMapper.readTree(stage.getResponse().getContentAsString()).get("id").asText();
		mockMvc
			.perform(post("/jobs/" + jobId + "/process/applications/" + applicationId + "/move")
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("stageId", stageId, "kind", "ADVANCE"))))
			.andExpect(status().isOk());

		mockMvc
			.perform(get("/jobs/" + jobId + "/process/applications/" + applicationId + "/audit")
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].action").value("RECRUITMENT_APPLICATION_MOVED"))
			.andExpect(jsonPath("$[0].metadata.kind").value("ADVANCE"))
			.andExpect(jsonPath("$[1].action").value("RECRUITMENT_APPLICATION_EVALUATED"))
			.andExpect(jsonPath("$[2].action").value("RECRUITMENT_APPLICATION_NOTED"))
			.andExpect(jsonPath("$[2].actorId").value(employer.userId()))
			.andExpect(jsonPath("$[2].targetId").value("job_application:" + applicationId));
	}

	@Test
	void candidatoEEstrangeiroNaoLeemAAuditoria() throws Exception {
		var employer = auth.authenticate("audit_owner2");
		var candidate = auth.authenticate("audit_candidate2");
		auth.activateProfessional(candidate);
		String companyId = createPage(employer, "Página de auditoria 2");
		String jobId = createJob(employer, companyId, "audit-vaga-2");
		String applicationId = apply(jobId, candidate);

		mockMvc
			.perform(get("/jobs/" + jobId + "/process/applications/" + applicationId + "/audit")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isForbidden());

		var outsider = auth.authenticate("audit_outsider");
		mockMvc
			.perform(get("/jobs/" + jobId + "/process/applications/" + applicationId + "/audit")
				.header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden());

		// Candidatura de outra vaga na URL: 404, não vaza existência.
		String jobId2 = createJob(employer, companyId, "audit-vaga-3");
		mockMvc
			.perform(get("/jobs/" + jobId2 + "/process/applications/" + applicationId + "/audit")
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isNotFound());
	}

	private String createPage(TestAuthHelper.AuthSession who, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/empresas")
				.header("X-Session-Token", who.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String createJob(TestAuthHelper.AuthSession session, String companyId, String idempotencyKey)
			throws Exception {
		String payload = """
				{"title":"Pessoa Desenvolvedora","company":"Acme","location":"Remoto","type":"CLT",
				 "level":"PLENO","currency":"BRL","salaryMin":"1500","salaryPeriod":"MONTH",
				 "description":"Vaga de teste do processo seletivo.","skills":["Java"],
				 "communityAgreement":true,"applicationFields":[],"companyId":"%s"}""";
		MvcResult result = mockMvc.perform(post("/jobs")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload.formatted(companyId)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String apply(String jobId, TestAuthHelper.AuthSession candidate) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "audit-apply-" + jobId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"Tenho interesse\",\"answers\":[]}"))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

}
