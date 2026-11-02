package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** O denunciante acompanha as PROPRIAS denuncias (estado, motivo, alvo) — e nao as de outros. */
@SpringBootTest
@AutoConfigureMockMvc
class MyReportsTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String postAndReport(TestAuthHelper.AuthSession reporter, String content) throws Exception {
		Map<String, Object> post = Map.of("topic", "TECNOLOGIA", "type", "INSIGHT", "content", content, "tags", List.of(),
			"mediaIds", List.of());
		MvcResult created = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", reporter.token())
				.header("Idempotency-Key", "myrep-" + reporter.userId() + "-" + content)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(post)))
			.andReturn();
		if (created.getResponse().getStatus() != 201) {
			System.out.println("DEBUG post status=" + created.getResponse().getStatus()
				+ " body=" + created.getResponse().getContentAsString());
		}
		assertThat(created.getResponse().getStatus())
			.as("criar post: " + created.getResponse().getContentAsString()).isEqualTo(201);
		String postId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		Map<String, Object> report = Map.of("targetType", "POST", "targetId", postId,
			"reason", "SCAM", "description", "Parece golpe de investimento");
		MvcResult reported = mockMvc.perform(post("/reports")
				.header("X-Session-Token", reporter.token())
				.header("Idempotency-Key", "myrep-report-" + reporter.userId() + "-" + postId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(report)))
			.andReturn();
		assertThat(reported.getResponse().getStatus())
			.as("denunciar: " + reported.getResponse().getContentAsString()).isEqualTo(201);
		return postId;
	}

	@Test
	void reporterSeesOwnReportsWithStatus() throws Exception {
		var reporter = new TestAuthHelper(mockMvc, objectMapper).authenticate("myrep_reporter");
		String postId = postAndReport(reporter, "conteudo denunciado");

		MvcResult result = mockMvc.perform(get("/reports/mine").header("X-Session-Token", reporter.token()))
			.andExpect(status().isOk()).andReturn();
		JsonNode reports = objectMapper.readTree(result.getResponse().getContentAsString());

		boolean found = false;
		for (int i = 0; i < reports.size(); i++) {
			JsonNode item = reports.get(i);
			if (postId.equals(item.path("targetId").asString())) {
				found = true;
				assertThat(item.path("targetType").asString()).isEqualTo("POST");
				assertThat(item.path("reason").asString()).isEqualTo("SCAM");
				assertThat(item.path("status").asString()).isIn("PENDING", "TRIAGED");
				// O denunciante nao ve dados pessoais de terceiros nem triagem interna.
				assertThat(item.has("aiTriage")).isFalse();
			}
		}
		assertThat(found).isTrue();
	}

	@Test
	void otherUsersDontSeeReportsTheyDidNotMake() throws Exception {
		var reporter = new TestAuthHelper(mockMvc, objectMapper).authenticate("myrep_reporter2");
		var other = new TestAuthHelper(mockMvc, objectMapper).authenticate("myrep_other2");
		String postId = postAndReport(reporter, "denuncia alheia");

		MvcResult result = mockMvc.perform(get("/reports/mine").header("X-Session-Token", other.token()))
			.andExpect(status().isOk()).andReturn();
		JsonNode reports = objectMapper.readTree(result.getResponse().getContentAsString());
		for (int i = 0; i < reports.size(); i++) {
			assertThat(reports.get(i).path("targetId").asString()).isNotEqualTo(postId);
		}
	}

	@Test
	void reportsMineRequiresAuthentication() throws Exception {
		MvcResult result = mockMvc.perform(get("/reports/mine")).andReturn();
		System.out.println("DEBUG mine status=" + result.getResponse().getStatus()
			+ " body=" + result.getResponse().getContentAsString());
		assertThat(result.getResponse().getStatus()).isEqualTo(401);
	}
}
