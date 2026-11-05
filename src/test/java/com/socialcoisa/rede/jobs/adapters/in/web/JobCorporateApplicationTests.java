package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * A candidatura carrega a IDENTIDADE PROFISSIONAL quando ela existe e está ativa: nome,
 * headline e industria vêm do alterego; os valores da conta (reputação, experiência,
 * habilidades, currículo) são os mesmos das duas identidades. Sem perfil profissional
 * ativo, o retrato continua sendo o social — vagas funcionam para todo mundo.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JobCorporateApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private ReputationService reputationService;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		// Mesmo padrão dos testes de vaga: a criação exige reputação > 150.
		when(reputationService.reputationOf(anyString())).thenReturn(300);
	}

	private String createJob(TestAuthHelper.AuthSession owner) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "job-corp-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"title", "Vaga com identidade", "company", "Énóis", "location", "Remoto",
					"type", "FULL_TIME", "level", "SENIOR", "skills", java.util.List.of(),
					// A vaga nova exige descrição e valor (item 5 do backlog).
					"description", "Vaga de teste com valor.", "salaryMin", "1500",
					"communityAgreement", true))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void apply(TestAuthHelper.AuthSession applicant, String jobId) throws Exception {
		mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", applicant.token())
				.header("Idempotency-Key", "apply-corp-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("message", "Quero a vaga."))))
			.andExpect(status().isCreated());
	}

	@Test
	void corporateApplicantUsesTheProfessionalIdentity() throws Exception {
		var owner = auth.authenticate("jobcorp_owner1");
		var applicant = auth.authenticate("jobcorp_prof");
		String jobId = createJob(owner);

		// Ativa o perfil profissional com nome e headline próprios.
		mockMvc.perform(post("/me/business-profile")
				.header("X-Session-Token", applicant.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"username", "jobcorp.pro", "name", "Nome Profissional da Pessoa"))))
			.andExpect(status().isOk());
		mockMvc.perform(patch("/me/business-profile")
				.header("X-Session-Token", applicant.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"headline", "Engenheira de plataforma", "industry", "Tecnologia"))))
			.andExpect(status().isOk());
		mockMvc.perform(post("/me/business-profile/activate")
				.header("X-Session-Token", applicant.token()))
			.andExpect(status().isOk());

		apply(applicant, jobId);

		mockMvc.perform(get("/jobs/" + jobId + "/applications").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].candidate.identity").value("CORPORATE"))
			.andExpect(jsonPath("$[0].candidate.name").value("Nome Profissional da Pessoa"))
			.andExpect(jsonPath("$[0].candidate.headline").value("Engenheira de plataforma"))
			.andExpect(jsonPath("$[0].candidate.industry").value("Tecnologia"));
	}

	@Test
	void applicationAndItsConversationLiveInTheProfessionalContext() throws Exception {
		var owner = auth.authenticate("jobcorp_owner3");
		var applicant = auth.authenticate("jobcorp_ctx");
		String proUsername = auth.activateProfessional(applicant);
		String jobId = createJob(owner);

		apply(applicant, jobId);

		// A empresa ve a identidade profissional, com o @ profissional (nunca o social).
		mockMvc.perform(get("/jobs/" + jobId + "/applications").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].candidate.identity").value("CORPORATE"))
			.andExpect(jsonPath("$[0].candidate.username").value(proUsername));

		// A conversa da candidatura esta na caixa profissional, com a identidade profissional.
		mockMvc.perform(get("/conversations").param("context", "PROFESSIONAL")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.kind == 'JOB')].context").value(org.hamcrest.Matchers.hasItem("PROFESSIONAL")))
			.andExpect(jsonPath("$.items[?(@.kind == 'JOB')].identities." + applicant.userId() + ".username")
				.value(org.hamcrest.Matchers.hasItem(proUsername)));

		// E NAO aparece na caixa social de nenhum dos dois.
		mockMvc.perform(get("/conversations").param("context", "SOCIAL").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.kind == 'JOB')]").isEmpty());
		mockMvc.perform(get("/conversations").param("context", "SOCIAL").header("X-Session-Token", applicant.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.kind == 'JOB')]").isEmpty());
	}

	@Test
	void socialDirectMessageNeverReusesTheApplicationConversation() throws Exception {
		var owner = auth.authenticate("jobcorp_owner4");
		var applicant = auth.authenticate("jobcorp_dm");
		auth.activateProfessional(applicant);
		String jobId = createJob(owner);
		apply(applicant, jobId);

		MvcResult professional = mockMvc.perform(get("/conversations").param("context", "PROFESSIONAL")
				.header("X-Session-Token", applicant.token()))
			.andExpect(status().isOk())
			.andReturn();
		String jobConversation = objectMapper.readTree(professional.getResponse().getContentAsString())
			.path("items").get(0).path("id").asText();

		MvcResult direct = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", applicant.token())
				.header("Idempotency-Key", "jobcorp-dm-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("userId", owner.userId()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.context").value("SOCIAL"))
			.andReturn();
		String directConversation = objectMapper.readTree(direct.getResponse().getContentAsString()).path("id").asText();

		org.assertj.core.api.Assertions.assertThat(directConversation).isNotEqualTo(jobConversation);
	}

	@Test
	void applicantWithoutAnActiveProfessionalIdentityIsRefused() throws Exception {
		var owner = auth.authenticate("jobcorp_owner2");
		var applicant = auth.authenticate("jobcorp_social");
		String jobId = createJob(owner);

		mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", applicant.token())
				.header("Idempotency-Key", "apply-social-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("message", "Quero a vaga."))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NO_ACTIVE_CORPORATE_PROFILE"));

		// Nada foi registrado nem entregue em nome da identidade social.
		mockMvc.perform(get("/jobs/" + jobId + "/applications").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());
	}
}
