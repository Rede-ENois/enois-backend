package com.socialcoisa.rede.companies.adapters.in.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
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
 * A equipe de recrutamento da página: papéis validados no backend, capability por operação,
 * e as vagas da organização commandadas pelo papel — sem abrir para quem não é da equipe.
 * Isolamento entre organizações e os caminhos de recusa (403/404) são parte da prova.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecruitmentTeamTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	/** Same overrides as JobApplicationFlowTests, so both share one test context. */
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
	void teamByRoleCommandsTheJobAndOtherOrganizationStaysOut() throws Exception {
		var owner = auth.authenticate("rt_owner");
		var manager = auth.authenticate("rt_manager");
		var observer = auth.authenticate("rt_observer");
		auth.activateProfessional(observer);
		var outsider = auth.authenticate("rt_outsider");
		var candidate = auth.authenticate("rt_candidate");
		auth.activateProfessional(candidate);
		String uid = UUID.randomUUID().toString().substring(0, 8);

		// Página da organização A, com gestor e observador na equipe de recrutamento.
		String companyId = createPage(owner, "Recruta Page " + uid);
		pageMember(owner, companyId, manager.userId(), "RECRUITER");
		addTeamMember(owner, companyId, manager.userId(), "RECRUITMENT_MANAGER");
		addTeamMember(owner, companyId, observer.userId(), "OBSERVER");

		// Página de outra organização, sem relação com a primeira.
		String otherCompanyId = createPage(outsider, "Other Page " + uid);

		// O gestor publica pela página — role de página RECRUITER vem daqui para baixo.
		String jobId = postJob(manager, companyId, "Vaga da equipe " + uid);

		// O candidato se candidata.
		String applicationId = apply(candidate, jobId);

		// A EQUIPE comanda pelo papel: gestor move (TRIAGE), observador não.
		mockMvc.perform(post("/jobs/" + jobId + "/process/applications/" + applicationId + "/notes")
				.header("X-Session-Token", manager.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("body", "Bom perfil."))))
			.andExpect(status().isOk());

		mockMvc.perform(post("/jobs/" + jobId + "/process/applications/" + applicationId + "/notes")
				.header("X-Session-Token", observer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("body", "Espionando."))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));

		// Quem não é da equipe da página continua de fora — inclusive o dono de OUTRA página.
		mockMvc.perform(get("/jobs/" + jobId + "/process/applications")
				.header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));

		// O gestor vê as candidaturas da vaga da organização.
		mockMvc.perform(get("/jobs/" + jobId + "/process/applications")
				.header("X-Session-Token", manager.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].applicationId").value(applicationId));

		// O dono da página lista e muda o papel da equipe; o observador não gerencia equipe.
		mockMvc.perform(get("/empresas/" + companyId + "/recrutamento/equipe")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2));

		mockMvc.perform(patch("/empresas/" + companyId + "/recrutamento/equipe/" + observer.userId())
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("role", "INTERVIEWER"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("INTERVIEWER"));

		mockMvc.perform(patch("/empresas/" + companyId + "/recrutamento/equipe/" + manager.userId())
				.header("X-Session-Token", observer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("role", "OBSERVER"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("RECRUITMENT_FORBIDDEN"));

		// Isolamento entre organizações: a equipe da página A não lista a da página B.
		mockMvc.perform(get("/empresas/" + otherCompanyId + "/recrutamento/equipe")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isForbidden());

		// O papel do dono é ORG_ADMIN de fato; um forasteiro não tem papel.
		mockMvc.perform(get("/empresas/" + companyId + "/recrutamento/eu")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("ORG_ADMIN"));
		mockMvc.perform(get("/empresas/" + companyId + "/recrutamento/eu")
				.header("X-Session-Token", outsider.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").doesNotExist());

		// O dono remove um membro; o removido perde o acesso na hora.
		addTeamMember(owner, companyId, outsider.userId(), "OBSERVER");
		mockMvc.perform(get("/jobs/" + jobId + "/process/applications")
				.header("X-Session-Token", outsider.token()))
			.andExpect(status().isOk());
		mockMvc.perform(delete("/empresas/" + companyId + "/recrutamento/equipe/" + outsider.userId())
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/jobs/" + jobId + "/process/applications")
				.header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));

		// Membro duplicado não entra; papel inexistente é recusado.
		mockMvc.perform(post("/empresas/" + companyId + "/recrutamento/equipe")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", manager.userId(), "role", "RECRUITER"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
		mockMvc.perform(post("/empresas/" + companyId + "/recrutamento/equipe")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", candidate.userId(), "role", "BIG_BOSS"))))
			.andExpect(status().isBadRequest());
	}

	@Test
	void pageRecruiterSchedulesAndManagesInterviewsByRole() throws Exception {
		var owner = auth.authenticate("rt2_owner");
		var recruiter = auth.authenticate("rt2_recruiter");
		auth.activateProfessional(recruiter);
		var candidate = auth.authenticate("rt2_candidate");
		auth.activateProfessional(candidate);
		String uid = UUID.randomUUID().toString().substring(0, 8);

		String companyId = createPage(owner, "Interview Page " + uid);
		pageMember(owner, companyId, recruiter.userId(), "RECRUITER");
		addTeamMember(owner, companyId, recruiter.userId(), "RECRUITER");
		String jobId = postJob(recruiter, companyId, "Vaga de entrevista " + uid);
		String applicationId = apply(candidate, jobId);

		// RECRUITER tem MANAGE_INTERVIEWS: marca pelo papel, e fica como employerId da entrevista.
		Instant starts = Instant.parse("2027-06-10T17:00:00Z");
		MvcResult scheduled = mockMvc.perform(
				post("/jobs/" + jobId + "/applications/" + applicationId + "/interview")
					.header("X-Session-Token", recruiter.token())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("startsAt", starts.toString(),
						"endsAt", starts.plus(Duration.ofHours(1)).toString(),
						"timeZoneId", "America/Sao_Paulo"))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SCHEDULED"))
			.andReturn();

		// Quem marcou reagenda e cancela; a agenda dos dois lados acompanha (fonte JOB_INTERVIEW).
		String interviewId = objectMapper.readTree(scheduled.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(patch("/jobs/" + jobId + "/applications/" + applicationId + "/interview/" + interviewId)
				.header("X-Session-Token", recruiter.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"startsAt", starts.plus(Duration.ofDays(1)).toString(),
					"endsAt", starts.plus(Duration.ofDays(1)).plus(Duration.ofHours(1)).toString(),
					"timeZoneId", "America/Sao_Paulo"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.startsAt").value(starts.plus(Duration.ofDays(1)).toString()));

		mockMvc.perform(delete("/jobs/" + jobId + "/applications/" + applicationId + "/interview/" + interviewId)
				.header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"));

		// Um membro da equipe SEM MANAGE_INTERVIEWS (observador) não marca.
		var observer = auth.authenticate("rt2_observer");
		addTeamMember(owner, companyId, observer.userId(), "OBSERVER");
		var candidate2 = auth.authenticate("rt2_candidate2");
		auth.activateProfessional(candidate2);
		String applicationId2 = apply(candidate2, jobId);
		mockMvc.perform(post("/jobs/" + jobId + "/applications/" + applicationId2 + "/interview")
				.header("X-Session-Token", observer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("startsAt", starts.toString(),
					"endsAt", starts.plus(Duration.ofHours(1)).toString(),
					"timeZoneId", "America/Sao_Paulo"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));
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

	/** Membro da PÁGINA (CompanyRole) — é o que autoriza a publicar vagas em nome dela. */
	private void pageMember(TestAuthHelper.AuthSession who, String companyId, String userId, String role)
			throws Exception {
		mockMvc.perform(post("/empresas/" + companyId + "/members")
				.header("X-Session-Token", who.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", userId, "role", role))))
			.andExpect(status().isOk());
	}

	private MvcResult addTeamMember(TestAuthHelper.AuthSession who, String companyId, String userId, String role)
			throws Exception {
		return mockMvc.perform(post("/empresas/" + companyId + "/recrutamento/equipe")
				.header("X-Session-Token", who.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", userId, "role", role))))
			.andExpect(status().isOk())
			.andReturn();
	}

	private String postJob(TestAuthHelper.AuthSession who, String companyId, String title) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
				.header("X-Session-Token", who.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"title":"%s","company":"Acme","location":"Remoto","type":"CLT","level":"PLENO",
					 "currency":"BRL","salaryMin":"1500","salaryPeriod":"MONTH","description":"Vaga de teste.",
					 "skills":["Java"],"communityAgreement":true,"applicationFields":[],
					 "companyId":"%s"}""".formatted(title, companyId)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String apply(TestAuthHelper.AuthSession who, String jobId) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", who.token())
				.header("Idempotency-Key", "rt-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"answers\":[]}"))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
