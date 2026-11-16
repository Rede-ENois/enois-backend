package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

/**
 * Contenção do ataque "probe": o cadastro com o padrão não nasce; repeated hits pause signups from
 * that IP for a while, but never write blocked_ips and never block login. Headers sent by the client
 * cannot pick which address gets paused.
 *
 * O portão fica desligado no perfil padrão da suíte (as contas de teste usam "probe..."); aqui
 * ele é ligado só neste contexto.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SuspiciousSignupGuardTests {

	private static final String LOOPBACK = "127.0.0.1";
	private static final String FORGED = "198.51.100.250";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("rede.security.signup-guard-enabled", () -> "true");
	}

	@Test
	void repeatedAttackSignupsPauseTheDeviceButNeverBlockTheIp() throws Exception {
		// Forged IP headers from the client: none of them may become the paused/blocked address.
		for (int i = 1; i <= 3; i++) {
			mockMvc.perform(post("/auth/signup")
					.header("Idempotency-Key", "guard-signup-" + i)
					.header("X-Device-Fingerprint", "device-guard-1")
					.header("X-Real-IP", FORGED)
					.header("CF-Connecting-IP", FORGED)
					.contentType(MediaType.APPLICATION_JSON)
					.content(signup("Probe radica", "radica" + i + "@test.com")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SIGNUP_REFUSED"));
		}

		assertThat(jdbc.queryForList("select ip from blocked_ips where ip in (?, ?)", String.class, LOOPBACK, FORGED))
			.isEmpty();

		// Login from the same address is untouched (401: the account does not exist).
		mockMvc.perform(post("/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"email", "quem-nao-existe@test.com", "password", "Test1234!"))))
			.andExpect(status().isUnauthorized());

		// The device that repeated the pattern is paused for a while, even with a normal name...
		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "guard-signup-normal")
				.header("X-Device-Fingerprint", "device-guard-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(signup("Pessoa Normal", "normal@test.com")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("SIGNUP_REFUSED"));

		// ...while another person on the same address keeps signing up.
		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "guard-signup-neighbour")
				.header("X-Device-Fingerprint", "device-guard-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(signup("Pessoa Vizinha", "vizinha@test.com")))
			.andExpect(status().isCreated());
	}

	private String signup(String name, String email) throws Exception {
		return objectMapper.writeValueAsString(Map.of("name", name, "role", "USER", "email", email,
			"password", "Test1234!", "acceptedTerms", true, "birthDate", "1990-05-10"));
	}
}
