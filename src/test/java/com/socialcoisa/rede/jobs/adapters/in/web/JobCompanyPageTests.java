package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
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
 * A job posted for a company page: only the page team (RECRUITER and up) can do it, the job
 * carries the page name and slug, shows on the page and tells the page followers.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JobCompanyPageTests {

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
	void onlyThePageTeamPostsForItAndTheJobPointsBackToThePage() throws Exception {
		var owner = auth.authenticate("jcp_owner");
		var recruiter = auth.authenticate("jcp_recruiter");
		var outsider = auth.authenticate("jcp_outsider");
		var fan = auth.authenticate("jcp_fan");
		String uid = UUID.randomUUID().toString().substring(0, 8);
		JsonNode page = json(mockMvc.perform(post("/empresas")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Jobs Page " + uid))))
			.andExpect(status().isOk())
			.andReturn());
		String companyId = page.get("id").asText();
		String slug = page.get("slug").asText();
		mockMvc.perform(post("/empresas/" + companyId + "/members")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", recruiter.userId(), "role", "RECRUITER"))))
			.andExpect(status().isOk());
		mockMvc.perform(post("/empresas/" + companyId + "/follow").header("X-Session-Token", fan.token()))
			.andExpect(status().isOk());

		mockMvc.perform(createJob(outsider, companyId, "Vaga de fora " + uid))
			.andExpect(status().isForbidden());

		String title = "Vaga da pagina " + uid;
		JsonNode job = json(mockMvc.perform(createJob(recruiter, companyId, title))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.company").value("Jobs Page " + uid))
			.andExpect(jsonPath("$.companySlug").value(slug))
			.andReturn());
		String jobId = job.get("id").asText();

		mockMvc.perform(get("/jobs/" + jobId))
			.andExpect(jsonPath("$.companySlug").value(slug));
		mockMvc.perform(get("/jobs").param("query", uid).param("source", "INTERNAL"))
			.andExpect(jsonPath("$.items[?(@.id == '" + jobId + "')].companySlug").value(slug));
		// Editing keeps the job on the page, under the page name.
		mockMvc.perform(put("/jobs/" + jobId)
				.header("X-Session-Token", recruiter.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(jobBody(title, "Outro Nome", null)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.company").value("Jobs Page " + uid))
			.andExpect(jsonPath("$.companySlug").value(slug));

		mockMvc.perform(get("/empresa/" + slug))
			.andExpect(jsonPath("$.jobs[0].id").value(jobId))
			.andExpect(jsonPath("$.jobs[0].title").value(title))
			.andExpect(jsonPath("$.jobs[0].type").value("CLT"))
			.andExpect(jsonPath("$.jobs[0].createdAt").isNotEmpty())
			.andExpect(jsonPath("$.stats.openJobs").value(1));
		mockMvc.perform(get("/empresas/diretorio").param("query", uid))
			.andExpect(jsonPath("$[?(@.slug == '" + slug + "')].openJobs").value(1));

		mockMvc.perform(get("/notifications").param("context", "PROFESSIONAL").header("X-Session-Token", fan.token()))
			.andExpect(jsonPath("$.items[?(@.kind == 'COMPANY_NEW_OPPORTUNITY' && @.href == '/empresa/" + slug
				+ "')].context").value("PROFESSIONAL"));
	}

	@Test
	void aJobWithoutAPageHasNoSlug() throws Exception {
		var poster = auth.authenticate("jcp_plain");
		mockMvc.perform(createJob(poster, null, "Vaga sem pagina"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.company").value("Acme"))
			.andExpect(jsonPath("$.companySlug").doesNotExist());
	}

	private org.springframework.test.web.servlet.RequestBuilder createJob(TestAuthHelper.AuthSession who,
			String companyId, String title) {
		return post("/jobs")
			.header("X-Session-Token", who.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(jobBody(title, "Acme", companyId));
	}

	private static String jobBody(String title, String company, String companyId) {
		return """
				{"title":"%s","company":"%s","location":"Remoto","type":"CLT","level":"PLENO",
				 "currency":"BRL","salaryMin":"1500","salaryPeriod":"MONTH","description":"Vaga de teste com valor.",
				 "skills":["Java"],"communityAgreement":true,"applicationFields":[]%s}"""
			.formatted(title, company, companyId == null ? "" : ",\"companyId\":\"" + companyId + "\"");
	}

	private JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}
}
