package com.socialcoisa.rede.feed.adapters.in.web;

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

import tools.jackson.databind.ObjectMapper;

/**
 * Política de publicações: publicar exige SESSAO DE USUARIO. Nao existe caminho de token de
 * integracao (API-key/scope) que publique um post — e o que impede automacao externa de contornar.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostPublishingPolicyTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void publishingWithoutUserSessionIsRejected() throws Exception {
		mockMvc.perform(post("/feed/posts")
				.header("Idempotency-Key", "policy-anon")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "tentativa automatica", "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void serviceTokenHeaderDoesNotPublish() throws Exception {
		// Um token de servico/integracao inventado nao vira publicacao: nao ha esse caminho.
		mockMvc.perform(post("/feed/posts")
				.header("X-Integration-Token", "fake-service-token")
				.header("Idempotency-Key", "policy-service")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "tentativa via integracao", "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isUnauthorized());
	}
}
