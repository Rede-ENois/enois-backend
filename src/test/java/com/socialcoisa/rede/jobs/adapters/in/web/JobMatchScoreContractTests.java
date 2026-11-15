package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

/**
 * O matchScore e compatibilidade ENTRE o perfil de quem ve e a vaga: nunca e afirmacao da fonte
 * sobre a vaga. Visitante nao logado nao tem perfil, entao nao tem score (0 = a tela nao exibe).
 * O dono da vaga tambem fica em 0: nao faz sentido recomendar a propria vaga a si mesmo.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JobMatchScoreContractTests {

	private static final String PREFIX = "zms";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void authenticatedViewerWithMatchingProfileGetsANonZeroScore() throws Exception {
		var viewer = auth.authenticate("zms_viewer");
		updateProfile(viewer, List.of("Java", "Spring"), "São Paulo", "SP");

		// Sem viewer (sem token), a mesma vaga nao pode trazer score.
		TestDatabase.insertJob(PREFIX + "-java-sp", "Pessoa Desenvolvedora Java", "Acme", "São Paulo, SP",
			"CLT", "PLENO", List.of("Java", "Spring"), Instant.now().plusSeconds(3600));

		// A busca casa por titulo/empresa/skills (nao por id): "Java" basta, e o id procurado
		// isolado na resposta garante que o score avaliado e desta vaga.
		MvcResult anonymous = mockMvc.perform(get("/jobs").param("query", "Java").param("limit", "50"))
			.andExpect(status().isOk()).andReturn();
		assertThatFirstItemHasScore(anonymous, 0);

		MvcResult logged = mockMvc.perform(get("/jobs").param("query", "Java").param("limit", "50")
				.header("X-Session-Token", viewer.token()))
			.andExpect(status().isOk()).andReturn();
		JsonNode item = firstOfMine(logged, PREFIX + "-java-sp");
		org.assertj.core.api.Assertions.assertThat(item.get("matchScore").asInt()).isGreaterThan(0);
	}

	@Test
	void ownerSeesZeroScoreOnOwnJob() throws Exception {
		var owner = auth.authenticate("zms_owner");
		updateProfile(owner, List.of("Java"), "São Paulo", "SP");

		String jobId = PREFIX + "-own-" + owner.userId();
		TestDatabase.insertJob(jobId, "Vaga do proprio dono", "Coiso", "São Paulo, SP", "CLT", "PLENO",
			List.of("Java"), Instant.now().plusSeconds(3600));
		TestDatabase.setJobPostedBy(jobId, owner.userId());

		mockMvc.perform(get("/jobs/" + jobId).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.owner").value(true))
			.andExpect(jsonPath("$.matchScore").value(0));
	}

	private void updateProfile(TestAuthHelper.AuthSession session, List<String> skills, String city, String state)
			throws Exception {
		mockMvc.perform(patch("/me/profile")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "zms-profile-" + session.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("skills", skills, "city", city, "state", state))))
			.andExpect(status().isOk());
	}

	private void assertThatFirstItemHasScore(MvcResult result, int expected) throws Exception {
		JsonNode item = firstOfMine(result, PREFIX + "-java-sp");
		org.assertj.core.api.Assertions.assertThat(item.get("matchScore").asInt()).isEqualTo(expected);
	}

	private JsonNode firstOfMine(MvcResult result, String id) throws Exception {
		JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
		for (JsonNode item : items) {
			if (id.equals(item.get("id").asText())) {
				return item;
			}
		}
		throw new AssertionError("vaga " + id + " nao veio na resposta");
	}
}
