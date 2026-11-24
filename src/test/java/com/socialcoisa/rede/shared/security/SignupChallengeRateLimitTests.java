package com.socialcoisa.rede.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.Tag;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Emitir o desafio de cadastro é o passo anterior ao cadastro, então tem teto próprio: sem ele, o
 * cliente podia pedir desafios à vontade dentro da cota de cadastro e guardar vários resolvidos.
 */
@Tag("critico")
@SpringBootTest
@AutoConfigureMockMvc
class SignupChallengeRateLimitTests {

	@Autowired
	private MockMvc mockMvc;

	@DynamicPropertySource
	static void tightLimit(DynamicPropertyRegistry registry) {
		registry.add("rede.security.rate-limit.signup-challenge.limit", () -> 2);
		registry.add("rede.security.rate-limit.signup-challenge.window-minutes", () -> 60);
	}

	@Test
	void issuingChallengesHasItsOwnCeilingAndItsOwnBucket() throws Exception {
		String ip = "203.0.113.90";
		issue(ip).andExpect(status().isOk());
		issue(ip).andExpect(status().isOk());
		issue(ip).andExpect(status().isTooManyRequests());

		// A cota de cadastro não foi consumida: são ações distintas.
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post("/auth/signup")
				.header("X-Forwarded-For", ip)
				.header("Idempotency-Key", "challenge-cap-signup")
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Teste\",\"role\":\"USER\",\"email\":\"sem-arroba\",\"password\":\"curta\","
					+ "\"acceptedTerms\":true}"))
			.andExpect(status().isBadRequest());
	}

	private org.springframework.test.web.servlet.ResultActions issue(String ip) throws Exception {
		return mockMvc.perform(get("/auth/signup-challenge").header("X-Forwarded-For", ip));
	}
}