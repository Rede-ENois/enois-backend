package com.socialcoisa.rede.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class TestAuthHelper {

	public record AuthSession(String token, String userId) {}

	private final MockMvc mockMvc;
	private final ObjectMapper objectMapper;

	public TestAuthHelper(MockMvc mockMvc, ObjectMapper objectMapper) {
		this.mockMvc = mockMvc;
		this.objectMapper = objectMapper;
	}

	public AuthSession authenticate(String userId) throws Exception {
		String email = userId + "@test.com";
		String payload = objectMapper.writeValueAsString(Map.of(
			"name", userId,
			"role", "USER",
			"email", email,
			"password", "Test1234!"
		));

		MvcResult result = mockMvc.perform(post("/api/auth/signup")
				.header("Idempotency-Key", "signup-" + userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isCreated())
			.andReturn();

		JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
		return new AuthSession(
			node.get("token").asText(),
			node.get("user").get("id").asText()
		);
	}
}
