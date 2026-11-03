package com.socialcoisa.rede.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

/** Modo pânico: escrita vira 429, leitura continua, e o próprio painel consegue desligar. */
@SpringBootTest
@AutoConfigureMockMvc
class PanicModeTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@DynamicPropertySource
	static void admin(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "group-admin@test.com,panic-admin@test.com");
	}

	@Test
	void panicBlocksWritesAndCanBeTurnedOff() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("panic-admin");
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("panic-user");

		try {
			mockMvc.perform(post("/admin/panic")
					.header("X-Session-Token", admin.token())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"active\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(true));

			mockMvc.perform(post("/feed/posts")
					.header("X-Session-Token", user.token())
					.header("Idempotency-Key", "panic-post-1")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
						"type", "INSIGHT", "content", "Não deveria entrar durante o pânico.",
						"tags", List.of(), "mediaIds", List.of()))))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("PANIC_MODE"));

			// Leitura segue funcionando.
			mockMvc.perform(get("/feed").header("X-Session-Token", user.token()))
				.andExpect(status().isOk());

			// O próprio painel consegue desligar (endpoint isento).
			mockMvc.perform(post("/admin/panic")
					.header("X-Session-Token", admin.token())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"active\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));

			mockMvc.perform(post("/feed/posts")
					.header("X-Session-Token", user.token())
					.header("Idempotency-Key", "panic-post-2")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
						"type", "INSIGHT", "content", "Agora entra normalmente.",
						"tags", List.of(), "mediaIds", List.of()))))
				.andExpect(status().isCreated());
		}
		finally {
			mockMvc.perform(post("/admin/panic")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"active\":false}"));
		}
	}
}
