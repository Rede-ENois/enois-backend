package com.socialcoisa.rede.profile.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest
@AutoConfigureMockMvc
class ProfileControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String sessionToken;
	private String userId;

	@BeforeEach
	void setUp() throws Exception {
		String unique = "prof_" + System.nanoTime();
		String signupPayload = objectMapper.writeValueAsString(Map.of(
			"name", "Profile Test User",
			"role", "Engineer",
			"email", unique + "@example.com",
			"password", "senha-segura"));

		MvcResult signup = mockMvc.perform(post("/api/auth/signup")
				.header("Idempotency-Key", "prof-setup-" + unique)
				.contentType(MediaType.APPLICATION_JSON)
				.content(signupPayload))
			.andExpect(status().isCreated())
			.andReturn();

		JsonNode node = objectMapper.readTree(signup.getResponse().getContentAsString());
		sessionToken = node.get("token").asText();
		userId = node.get("user").get("id").asText();
	}

	@Test
	void updatesProfile() throws Exception {
		String profilePayload = objectMapper.writeValueAsString(Map.of(
			"headline", "Senior Engineer",
			"company", "Connect Grow",
			"location", "Remoto",
			"bio", "Fullstack developer",
			"skills", List.of("Java", "Spring"),
			"openToWork", true));

		mockMvc.perform(patch("/api/me/profile")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-update-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(profilePayload))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.headline").value("Senior Engineer"))
			.andExpect(jsonPath("$.company").value("Connect Grow"))
			.andExpect(jsonPath("$.skills[0]").value("Java"));

		mockMvc.perform(get("/api/me")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.headline").value("Senior Engineer"));
	}

	@Test
	void managesExperiences() throws Exception {
		String expPayload = objectMapper.writeValueAsString(Map.of(
			"title", "Software Engineer",
			"company", "Tech Co",
			"startDate", "2020-01-01"));

		MvcResult expResult = mockMvc.perform(post("/api/me/experiences")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-exp-create-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(expPayload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value("Software Engineer"))
			.andReturn();

		String expId = objectMapper.readTree(expResult.getResponse().getContentAsString()).get("id").asText();

		String updateExpPayload = objectMapper.writeValueAsString(Map.of(
			"title", "Senior Software Engineer",
			"company", "Tech Co",
			"startDate", "2020-01-01"));

		mockMvc.perform(put("/api/me/experiences/{id}", expId)
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-exp-update-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(updateExpPayload))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Senior Software Engineer"));

		mockMvc.perform(delete("/api/me/experiences/{id}", expId)
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-exp-delete-1"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/api/me/experiences")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void managesEducation() throws Exception {
		String eduPayload = objectMapper.writeValueAsString(Map.of(
			"school", "USP",
			"degree", "BS",
			"field", "Computer Science",
			"startDate", "2015-01-01",
			"endDate", "2019-12-31"));

		MvcResult eduResult = mockMvc.perform(post("/api/me/education")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-edu-create-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(eduPayload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.school").value("USP"))
			.andReturn();

		String eduId = objectMapper.readTree(eduResult.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(put("/api/me/education/{id}", eduId)
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-edu-update-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"school", "USP",
					"degree", "MS",
					"field", "Computer Science",
					"startDate", "2015-01-01",
					"endDate", "2019-12-31"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.degree").value("MS"));

		mockMvc.perform(delete("/api/me/education/{id}", eduId)
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-edu-delete-1"))
			.andExpect(status().isOk());
	}

	@Test
	void managesCertifications() throws Exception {
		String certPayload = objectMapper.writeValueAsString(Map.of(
			"name", "AWS Certified",
			"issuer", "Amazon",
			"issueDate", "2023-06-01"));

		MvcResult certResult = mockMvc.perform(post("/api/me/certifications")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-cert-create-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(certPayload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("AWS Certified"))
			.andReturn();

		String certId = objectMapper.readTree(certResult.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(put("/api/me/certifications/{id}", certId)
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-cert-update-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "AWS Certified Solutions Architect",
					"issuer", "Amazon",
					"issueDate", "2023-06-01"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("AWS Certified Solutions Architect"));

		mockMvc.perform(delete("/api/me/certifications/{id}", certId)
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-cert-delete-1"))
			.andExpect(status().isOk());
	}

	@Test
	void viewsPublicProfile() throws Exception {
		mockMvc.perform(get("/api/me")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").isNotEmpty());
	}
}
