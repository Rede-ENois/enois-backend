package com.socialcoisa.rede.identity.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class AuthProfileFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void signsUpLogsInUpdatesProfileAndPublishesWithSession() throws Exception {
		String signupPayload = objectMapper.writeValueAsString(Map.of(
			"name", "Ana Souza",
			"role", "Product Manager",
			"email", "ana.flow@example.com",
			"password", "senha-segura"));

		MvcResult signup = mockMvc.perform(post("/api/auth/signup")
				.header("Idempotency-Key", "auth-signup-flow-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(signupPayload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.user.username").value("ana-souza"))
			.andReturn();

		JsonNode signupNode = objectMapper.readTree(signup.getResponse().getContentAsString());
		String firstToken = signupNode.get("token").asText();

		MvcResult replay = mockMvc.perform(post("/api/auth/signup")
				.header("Idempotency-Key", "auth-signup-flow-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(signupPayload))
			.andExpect(status().isCreated())
			.andReturn();

		assertThat(replay.getResponse().getContentAsString()).isEqualTo(signup.getResponse().getContentAsString());

		String loginPayload = objectMapper.writeValueAsString(Map.of(
			"email", "ana.flow@example.com",
			"password", "senha-segura"));

		MvcResult login = mockMvc.perform(post("/api/auth/login")
				.header("Idempotency-Key", "auth-login-flow-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginPayload))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.email").value("ana.flow@example.com"))
			.andReturn();

		String token = objectMapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
		assertThat(token).isNotBlank().isNotEqualTo(firstToken);

		mockMvc.perform(get("/api/auth/me")
				.header("X-Session-Token", token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("ana-souza"));

		String profilePayload = objectMapper.writeValueAsString(Map.of(
			"headline", "Product Manager em plataformas de comunidade",
			"company", "Connect Grow",
			"location", "Sao Paulo",
			"skills", List.of("Produto", "Discovery")));

		mockMvc.perform(patch("/api/me/profile")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", "profile-update-flow-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(profilePayload))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.headline").value("Product Manager em plataformas de comunidade"))
			.andExpect(jsonPath("$.skills[0]").value("Produto"));

		String postPayload = objectMapper.writeValueAsString(Map.of(
			"type", "INSIGHT",
			"content", "Sessao real integrada ao feed.",
			"tags", List.of("Backend"),
			"mediaIds", List.of()));

		mockMvc.perform(post("/api/feed/posts")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", "session-feed-post-flow-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(postPayload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorId").value(signupNode.get("user").get("id").asText()));
	}
}
