package com.socialcoisa.rede.companies.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Company pages as visitors and teams use them: the directory, following, the page header
 * (jobs, stats, reviews, what the viewer can do), proposals from the professional identity and
 * completing with a review. Every assertion is about data this class created (shared H2).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CompanyPagesTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void directoryListsPagesJobNamesAndExternalNamesMostOpportunitiesFirst() throws Exception {
		var owner = auth("cpages_dir_owner");
		String uid = uid();
		JsonNode page = createPage(owner, "Dir Page " + uid, "Setor" + uid);
		publishProject(owner, page.get("id").asText());
		Instant now = Instant.now();
		TestDatabase.insertJob("job_dir_a_" + uid, "Vaga A", "Dir Jobs " + uid, "Remoto", "CLT", "PLENO", List.of(), now);
		TestDatabase.insertJob("job_dir_b_" + uid, "Vaga B", "dir jobs " + uid, "Remoto", "CLT", "PLENO", List.of(), now);
		// Same name as the page, posted by someone outside it: never shown as its own entry.
		TestDatabase.insertJob("job_dir_c_" + uid, "Vaga C", "DIR PAGE " + uid, "Remoto", "CLT", "PLENO", List.of(), now);
		TestDatabase.insertExternalJob("ext_dir_" + uid, "Vaga externa", "Dir Ext " + uid, "Remoto",
			"https://example.com/vaga/" + uid, now);

		mockMvc.perform(get("/empresas/diretorio").param("query", uid))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			// Pages first, even with less activity than a name that only exists in jobs.
			.andExpect(jsonPath("$[0].kind").value("PAGE"))
			.andExpect(jsonPath("$[0].slug").value(page.get("slug").asText()))
			.andExpect(jsonPath("$[0].openProjects").value(1))
			.andExpect(jsonPath("$[0].openJobs").value(0))
			.andExpect(jsonPath("$[0].followers").value(0))
			.andExpect(jsonPath("$[0].sector").value("Setor" + uid))
			.andExpect(jsonPath("$[1].kind").value("JOBS"))
			.andExpect(jsonPath("$[1].openJobs").value(2))
			.andExpect(jsonPath("$[1].slug").doesNotExist())
			.andExpect(jsonPath("$[2].kind").value("EXTERNAL"))
			.andExpect(jsonPath("$[2].name").value("Dir Ext " + uid));

		// A sector narrows to pages (names on jobs have no sector).
		mockMvc.perform(get("/empresas/diretorio").param("sector", "setor" + uid))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].kind").value("PAGE"));
		mockMvc.perform(get("/empresas/setores"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@ == 'Setor" + uid + "')]").isNotEmpty());
	}

	@Test
	void followingIsIdempotentAndShowsOnThePage() throws Exception {
		var owner = auth("cpages_follow_owner");
		var fan = auth("cpages_follow_fan");
		JsonNode page = createPage(owner, "Follow Co " + uid(), null);
		String id = page.get("id").asText();
		String slug = page.get("slug").asText();

		for (int i = 0; i < 2; i++) {
			mockMvc.perform(post("/empresas/" + id + "/follow").header("X-Session-Token", fan.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.following").value(true))
				.andExpect(jsonPath("$.followers").value(1));
		}
		mockMvc.perform(get("/empresa/" + slug).header("X-Session-Token", fan.token()))
			.andExpect(jsonPath("$.following").value(true))
			.andExpect(jsonPath("$.stats.followers").value(1));
		// The team can follow its own page too.
		mockMvc.perform(post("/empresas/" + id + "/follow").header("X-Session-Token", owner.token()))
			.andExpect(jsonPath("$.followers").value(2));
		for (int i = 0; i < 2; i++) {
			mockMvc.perform(delete("/empresas/" + id + "/follow").header("X-Session-Token", fan.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.following").value(false))
				.andExpect(jsonPath("$.followers").value(1));
		}
		mockMvc.perform(get("/empresa/" + slug))
			.andExpect(jsonPath("$.following").value(false))
			.andExpect(jsonPath("$.canPropose").value("SIGN_IN"));
		mockMvc.perform(post("/empresas/" + id + "/follow")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/empresas/comp_missing_" + uid() + "/follow").header("X-Session-Token", fan.token()))
			.andExpect(status().isNotFound());
	}

	@Test
	void proposalNeedsTheProfessionalIdentityAndCompletingLeavesAReview() throws Exception {
		var owner = auth("cpages_rev_owner");
		var pro = auth("cpages_rev_pro");
		JsonNode page = createPage(owner, "Review Co " + uid(), null);
		String slug = page.get("slug").asText();
		String projectId = publishProject(owner, page.get("id").asText());

		mockMvc.perform(get("/empresa/" + slug).header("X-Session-Token", owner.token()))
			.andExpect(jsonPath("$.canPropose").value("TEAM"))
			.andExpect(jsonPath("$.stats.openProjects").value(1))
			.andExpect(jsonPath("$.jobs").isEmpty())
			.andExpect(jsonPath("$.reviews").isEmpty());
		mockMvc.perform(get("/empresa/" + slug).header("X-Session-Token", pro.token()))
			.andExpect(jsonPath("$.canPropose").value("PROFESSIONAL_REQUIRED"));
		mockMvc.perform(propose(pro, projectId))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NO_ACTIVE_CORPORATE_PROFILE"));

		String proUsername = new TestAuthHelper(mockMvc, objectMapper).activateProfessional(pro);
		mockMvc.perform(get("/empresa/" + slug).header("X-Session-Token", pro.token()))
			.andExpect(jsonPath("$.canPropose").value("OK"));
		MvcResult sent = mockMvc.perform(propose(pro, projectId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SENT"))
			.andReturn();
		JsonNode proposal = objectMapper.readTree(sent.getResponse().getContentAsString());
		String conversationId = proposal.get("conversationId").asText();
		assertThat(conversationId).isNotBlank();

		mockMvc.perform(get("/microprojetos/" + projectId).header("X-Session-Token", owner.token()))
			.andExpect(jsonPath("$.proposals[0].conversationId").value(conversationId))
			.andExpect(jsonPath("$.proposals[0].person.identity").value("CORPORATE"))
			.andExpect(jsonPath("$.proposals[0].person.username").value(proUsername))
			.andExpect(jsonPath("$.proposals[0].person.href").value("/profissional/" + proUsername));
		// The proposal is posted in a professional conversation with the page owner.
		mockMvc.perform(get("/conversations/" + conversationId).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("FREELANCE"));
		mockMvc.perform(get("/conversations/" + conversationId + "/messages").header("X-Session-Token", owner.token()))
			.andExpect(jsonPath("$.items[0].from").value(pro.userId()))
			.andExpect(jsonPath("$.items[0].text").value(org.hamcrest.Matchers.containsString("Entrego em tres dias")));

		mockMvc.perform(post("/microprojetos/" + projectId + "/proposals/" + proposal.get("id").asText() + "/select")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());
		mockMvc.perform(complete(owner, projectId, Map.of("rating", 7)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_RATING"));
		mockMvc.perform(complete(owner, projectId, Map.of("text", "Sem nota")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_RATING"));
		mockMvc.perform(complete(owner, projectId, Map.of("rating", 5, "text", "Entrega impecavel e no prazo.")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"));

		mockMvc.perform(get("/empresa/" + slug))
			.andExpect(jsonPath("$.stats.openProjects").value(0))
			.andExpect(jsonPath("$.stats.completedProjects").value(1))
			.andExpect(jsonPath("$.reviews[0].projectId").value(projectId))
			.andExpect(jsonPath("$.reviews[0].projectTitle").value("Landing page da campanha de teste"))
			.andExpect(jsonPath("$.reviews[0].rating").value(5))
			.andExpect(jsonPath("$.reviews[0].text").value("Entrega impecavel e no prazo."))
			.andExpect(jsonPath("$.reviews[0].person.identity").value("CORPORATE"))
			.andExpect(jsonPath("$.reviews[0].person.href").value(startsWith("/profissional/")));
	}

	@Test
	void membersWithoutAProfessionalProfileShowTheSocialOne() throws Exception {
		var owner = auth("cpages_social_owner");
		JsonNode page = createPage(owner, "Social Co " + uid(), null);
		mockMvc.perform(get("/empresas/" + page.get("id").asText() + "/members").header("X-Session-Token", owner.token()))
			.andExpect(jsonPath("$[0].person.identity").value("SOCIAL"))
			.andExpect(jsonPath("$[0].person.href").value(startsWith("/perfil/")));
	}

	@Test
	void publishingAMicroprojectNotifiesFollowersButNotThePublisher() throws Exception {
		var owner = auth("cpages_notify_owner");
		var fan = auth("cpages_notify_fan");
		JsonNode page = createPage(owner, "Notify Co " + uid(), null);
		String id = page.get("id").asText();
		mockMvc.perform(post("/empresas/" + id + "/follow").header("X-Session-Token", fan.token()));
		mockMvc.perform(post("/empresas/" + id + "/follow").header("X-Session-Token", owner.token()));
		publishProject(owner, id);

		String href = "/empresa/" + page.get("slug").asText();
		mockMvc.perform(get("/notifications").param("context", "PROFESSIONAL").header("X-Session-Token", fan.token()))
			.andExpect(jsonPath("$.items[?(@.kind == 'COMPANY_NEW_OPPORTUNITY' && @.href == '" + href + "')]")
				.isNotEmpty());
		mockMvc.perform(get("/notifications").header("X-Session-Token", owner.token()))
			.andExpect(jsonPath("$.items[?(@.kind == 'COMPANY_NEW_OPPORTUNITY' && @.href == '" + href + "')]")
				.isEmpty());
	}

	/** Runs the V219 backfill again over rows of this test only: a name match alone never links. */
	@Test
	void backfillLinksAJobOnlyWhenItsPosterIsOnThePage() throws Exception {
		var owner = auth("cpages_backfill_owner");
		String uid = uid();
		String name = "Backfill Co " + uid;
		String companyId = createPage(owner, name, null).get("id").asText();
		Instant now = Instant.now();
		String linked = "job_bf_member_" + uid;
		String stranger = "job_bf_stranger_" + uid;
		String otherName = "job_bf_other_" + uid;
		TestDatabase.insertJob(linked, "Vaga", name.toUpperCase(), "Remoto", "CLT", "PLENO", List.of(), now);
		TestDatabase.setJobPostedBy(linked, owner.userId());
		TestDatabase.insertJob(stranger, "Vaga", name, "Remoto", "CLT", "PLENO", List.of(), now);
		TestDatabase.insertJob(otherName, "Vaga", "Outra " + uid, "Remoto", "CLT", "PLENO", List.of(), now);
		TestDatabase.setJobPostedBy(otherName, owner.userId());

		String migration = Files.readString(Path.of("src/main/resources/db/migration/V219__company_pages.sql"));
		int start = migration.indexOf("update jobs set company_id");
		String backfill = migration.substring(start, migration.indexOf(';', start))
			+ " and id in ('" + linked + "', '" + stranger + "', '" + otherName + "')";
		jdbc.update(backfill);

		assertThat(companyIdOf(linked)).isEqualTo(companyId);
		assertThat(companyIdOf(stranger)).isNull();
		assertThat(companyIdOf(otherName)).isNull();
	}

	private String companyIdOf(String jobId) {
		return jdbc.queryForObject("select company_id from jobs where id = ?", String.class, jobId);
	}

	private org.springframework.test.web.servlet.RequestBuilder propose(TestAuthHelper.AuthSession who,
			String projectId) throws Exception {
		return post("/microprojetos/" + projectId + "/proposals")
			.header("X-Session-Token", who.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of(
				"message", "Entrego em tres dias, ja fiz landing pages de campanha antes.")));
	}

	private org.springframework.test.web.servlet.RequestBuilder complete(TestAuthHelper.AuthSession who,
			String projectId, Map<String, Object> body) throws Exception {
		return post("/microprojetos/" + projectId + "/complete")
			.header("X-Session-Token", who.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body));
	}

	private JsonNode createPage(TestAuthHelper.AuthSession owner, String name, String sector) throws Exception {
		Map<String, Object> body = sector == null ? Map.of("name", name, "description", "Pagina de teste.")
			: Map.of("name", name, "description", "Pagina de teste.", "sector", sector);
		MvcResult created = mockMvc.perform(post("/empresas")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString());
	}

	private String publishProject(TestAuthHelper.AuthSession owner, String companyId) throws Exception {
		MvcResult project = mockMvc.perform(post("/empresas/" + companyId + "/microprojetos")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Landing page da campanha de teste",
					"problem", "Precisamos de uma landing page simples para a campanha de fim de ano.",
					"deliverables", "Pagina publicada", "acceptanceCriteria", "Carrega em menos de 2s"))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(project.getResponse().getContentAsString()).get("id").asText();
	}

	private static String uid() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}
}
