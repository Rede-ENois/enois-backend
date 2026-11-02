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
import com.socialcoisa.rede.shared.web.TestDatabase;
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
			"password", "senha-segura",
			"acceptedTerms", true,
			// A data de nascimento e obrigatoria no cadastro (regra do ECA: menor precisa de
			// responsavel) — sem ela o backend recusa com 400 BIRTH_DATE_REQUIRED.
			"birthDate", "1990-05-10"));

		// O cadastro nasce pendente: devolve para onde o link foi enviado, sem sessao.
		MvcResult signup = mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "auth-signup-flow-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(signupPayload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value("ana.flow@example.com"))
			.andExpect(jsonPath("$.verificationRequired").value(true))
			.andReturn();

		assertThat(signup.getResponse().getContentAsString()).doesNotContain("token");

		MvcResult replay = mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "auth-signup-flow-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(signupPayload))
			.andExpect(status().isCreated())
			.andReturn();

		assertThat(replay.getResponse().getContentAsString()).isEqualTo(signup.getResponse().getContentAsString());

		String loginPayload = objectMapper.writeValueAsString(Map.of(
			"email", "ana.flow@example.com",
			"password", "senha-segura"));

		mockMvc.perform(post("/auth/login")
				.header("Idempotency-Key", "auth-login-flow-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginPayload))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

		// Confirmacao do e-mail: no cadastro de DUAS etapas a conta nasce na confirmacao do link
		// (o token real tem teste proprio em EmailVerificationFlowTests). Aqui o token do
		// pendente e conhecido pelo teste, entao o link e usado de verdade.
		String confirmToken = "token-teste-auth-profile";
		TestDatabase.setPendingSignupToken("ana.flow@example.com", confirmToken);
		mockMvc.perform(post("/auth/email/verify")
				.header("Idempotency-Key", "auth-verify-flow-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", confirmToken))))
			.andExpect(status().isOk());
		String userId = TestDatabase.userIdByEmail("ana.flow@example.com").orElseThrow();

		MvcResult login = mockMvc.perform(post("/auth/login")
				.header("Idempotency-Key", "auth-login-flow-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginPayload))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.email").value("ana.flow@example.com"))
			.andExpect(jsonPath("$.user.emailVerified").value(true))
			.andReturn();

		String token = com.socialcoisa.rede.shared.web.TestAuthHelper.sessionToken(login);
		assertThat(token).isNotBlank();

		mockMvc.perform(get("/auth/me")
				.header("X-Session-Token", token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("ana-souza"));

		String profilePayload = objectMapper.writeValueAsString(Map.of(
			"headline", "Product Manager em plataformas de comunidade",
			"company", "Connect Grow",
			"location", "Sao Paulo",
			"skills", List.of("Produto", "Discovery")));

		mockMvc.perform(patch("/me/profile")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", "profile-update-flow-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(profilePayload))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.headline").value("Product Manager em plataformas de comunidade"))
			.andExpect(jsonPath("$.skills[0]").value("Produto"));

		String postPayload = objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
			"type", "INSIGHT",
			"content", "Sessao real integrada ao feed.",
			"tags", List.of("Backend"),
			"mediaIds", List.of()));

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", "session-feed-post-flow-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(postPayload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorId").value(userId));
	}
}
