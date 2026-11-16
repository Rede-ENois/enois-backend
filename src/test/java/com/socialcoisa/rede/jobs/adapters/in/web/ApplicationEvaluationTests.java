package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
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
 * Avaliações internas (incremento 7): parecer estruturado por avaliador — critérios com nota,
 * veredito e resumo — com as mesmas garantias das notas internas: quem move no pipeline (TRIAGE)
 * avalia, leitura é para quem vê candidatos, o candidato jamais vê, e uma avaliação por autor
 * (reavaliar sobrescreve, sem segunda linha).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationEvaluationTests {

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
	void avaliadorRegistraReavaliaSobrescrevendoECandidatoNuncaVe() throws Exception {
		var employer = auth.authenticate("eval_owner");
		var peer = auth.authenticate("eval_peer");
		var candidate = auth.authenticate("eval_candidate");
		auth.activateProfessional(candidate);
		String companyId = createPage(employer, "Página de avaliação 1");
		String jobId = createJob(employer, companyId, "eval-vaga-1");
		String applicationId = apply(jobId, candidate);
		pageMember(employer, companyId, peer.userId(), "RECRUITER");
		addTeamMember(employer, companyId, peer.userId(), "RECRUITMENT_MANAGER");

		assertThat(evaluate(employer, jobId, applicationId, criteria(), "YES", "Boa base, conversar.", 200))
			.isTrue();

		MvcResult list = mockMvc
			.perform(get("/jobs/" + jobId + "/process/applications/" + applicationId + "/evaluations")
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andReturn();
		String body = list.getResponse().getContentAsString();
		assertThat(body).contains("\"verdict\":\"YES\"").contains("Boa base");

		// Reavaliação do MESMO autor: sobrescreve, continua uma linha só, veredito novo.
		Map<String, Integer> novos = new LinkedHashMap<>(criteria());
		novos.put("Comunicação", 4);
		mockMvc
			.perform(put("/jobs/" + jobId + "/process/applications/" + applicationId + "/evaluations")
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("criteria", novos, "verdict", "STRONG_YES", "summary", "Aprovada na triagem."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.verdict").value("STRONG_YES"))
			.andExpect(jsonPath("$.criteria['Comunicação']").value(4));

		mockMvc.perform(get("/jobs/" + jobId + "/process/applications/" + applicationId + "/evaluations")
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1));

		// Segundo avaliador (equipe, TRIAGE): linha própria, as duas aparecem para quem vê candidatos.
		assertThat(evaluate(peer, jobId, applicationId, criteria(), "NO", null, 200)).isTrue();
		mockMvc.perform(get("/jobs/" + jobId + "/process/applications/" + applicationId + "/evaluations")
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2));

		// O candidato JAMAIS vê a avaliação da própria candidatura.
		mockMvc
			.perform(get("/jobs/" + jobId + "/process/applications/" + applicationId + "/evaluations")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isForbidden());
		mockMvc
			.perform(put("/jobs/" + jobId + "/process/applications/" + applicationId + "/evaluations")
				.header("X-Session-Token", candidate.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"criteria\":{\"Técnica\":5},\"verdict\":\"YES\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void regrasDePreenchimentoSaoExplicitasELeituraExigeVerCandidatos() throws Exception {
		var employer = auth.authenticate("eval_owner2");
		var candidate = auth.authenticate("eval_candidate2");
		auth.activateProfessional(candidate);
		String companyId = createPage(employer, "Página de avaliação 2");
		String jobId = createJob(employer, companyId, "eval-vaga-2");
		String applicationId = apply(jobId, candidate);

		// Veredito fora do catálogo: 422 com código próprio.
		mockMvc
			.perform(put("/jobs/" + jobId + "/process/applications/" + applicationId + "/evaluations")
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"criteria\":{\"Técnica\":5},\"verdict\":\"TALVEZ\"}"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("EVALUATION_VERDICT_INVALID"));

		// Nota fora de 0..10.
		assertThat(evaluate(employer, jobId, applicationId, Map.of("Técnica", 11), "YES", null, 422)).isTrue();
		// Sem critério algum.
		assertThat(evaluate(employer, jobId, applicationId, Map.of(), "YES", null, 422)).isTrue();

		// Estrangeiro sem vínculo com a vaga: 403 na leitura e na escrita.
		var outsider = auth.authenticate("eval_outsider");
		mockMvc.perform(get("/jobs/" + jobId + "/process/applications/" + applicationId + "/evaluations")
				.header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden());
		mockMvc
			.perform(put("/jobs/" + jobId + "/process/applications/" + applicationId + "/evaluations")
				.header("X-Session-Token", outsider.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"criteria\":{\"Técnica\":5},\"verdict\":\"YES\"}"))
			.andExpect(status().isForbidden());

		// Candidatura de outra vaga na URL não engana o gate.
		String jobId2 = createJob(employer, companyId, "eval-vaga-3");
		mockMvc.perform(get("/jobs/" + jobId2 + "/process/applications/" + applicationId + "/evaluations")
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isNotFound());
	}

	private boolean evaluate(TestAuthHelper.AuthSession evaluator, String jobId, String applicationId,
			Map<String, Integer> criteria, String verdict, String summary, int expectedStatus) throws Exception {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("criteria", criteria);
		payload.put("verdict", verdict);
		if (summary != null) {
			payload.put("summary", summary);
		}
		return mockMvc
			.perform(put("/jobs/" + jobId + "/process/applications/" + applicationId + "/evaluations")
				.header("X-Session-Token", evaluator.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(payload)))
			.andExpect(status().is(expectedStatus))
			.andReturn()
			.getResponse()
			.getStatus() == expectedStatus;
	}

	private Map<String, Integer> criteria() {
		Map<String, Integer> criteria = new LinkedHashMap<>();
		criteria.put("Técnica", 7);
		criteria.put("Fit cultural", 8);
		return criteria;
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

	private void pageMember(TestAuthHelper.AuthSession who, String companyId, String userId, String role)
			throws Exception {
		mockMvc.perform(post("/empresas/" + companyId + "/members")
				.header("X-Session-Token", who.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", userId, "role", role))))
			.andExpect(status().isOk());
	}

	private void addTeamMember(TestAuthHelper.AuthSession who, String companyId, String userId, String role)
			throws Exception {
		mockMvc.perform(post("/empresas/" + companyId + "/recrutamento/equipe")
				.header("X-Session-Token", who.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", userId, "role", role))))
			.andExpect(status().isOk());
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
				.header("Idempotency-Key", "eval-apply-" + jobId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"Tenho interesse\",\"answers\":[]}"))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

}
