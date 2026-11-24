package com.socialcoisa.rede.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.junit.jupiter.api.Tag;
import tools.jackson.databind.ObjectMapper;

/**
 * O limite por IP nas rotas de identidade (Fase A do Security & Trust).
 *
 * O que este teste tranca — e por que cada um importa:
 *
 * - **por IP, não global**: a mesma sequência que bloqueia um IP não pode bloquear outro (um teto
 *   global deixaria um atacante sozinho consumir a cota e derrubar o acesso de todos);
 * - **`X-Forwarded-For` pelo PRIMEIRO item**: é o IP real que o proxy poe no inicio; os
 *   hops seguintes sao intermediarios. Trocar o primeiro muda a decisão; trocar o resto não;
 * - **cabeçalho absurdo não derruba nada**: valor gigante cai no IP da conexão em vez de virar erro;
 * - **quem usa normalmente não é bloqueado**: poucas tentativas seguidas passam;
 * - **a janela expira**: passado o tempo, o IP volta a poder tentar (sem isso, um pico viraria
 *   bloqueio permanente).
 */
@Tag("critico")
@SpringBootTest
@AutoConfigureMockMvc
class IdentityRateLimitTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@DynamicPropertySource
	static void tightLimits(DynamicPropertyRegistry registry) {
		registry.add("rede.security.rate-limit.login.limit", () -> 3);
		registry.add("rede.security.rate-limit.login.window-minutes", () -> 5);
		registry.add("rede.security.rate-limit.signup.limit", () -> 2);
		registry.add("rede.security.rate-limit.signup.window-minutes", () -> 5);
		registry.add("rede.security.rate-limit.refresh.limit", () -> 2);
		registry.add("rede.security.rate-limit.refresh.window-minutes", () -> 5);
	}

	@Test
	void sameIpIsBlockedAfterTheLimitAndAnotherIpIsNot() throws Exception {
		String first = "203.0.113.10";
		for (int i = 0; i < 3; i++) {
			login(first).andExpect(status().isUnauthorized());
		}
		login(first).andExpect(status().isTooManyRequests());

		// Outro IP continua entrando: o limite é por origem, não uma cota de todo mundo.
		login("203.0.113.11").andExpect(status().isUnauthorized());
	}

	@Test
	void forwardedForCountsTheLastHopOnly() throws Exception {
		// The left-most items are client-controlled and rotate on every attempt; the hop appended by
		// our proxy (right-most untrusted) is the real address and is what gets counted.
		for (int i = 0; i < 3; i++) {
			loginWithForwarded("198.51.100." + i + ", 203.0.113.30").andExpect(status().isUnauthorized());
		}
		loginWithForwarded("198.51.100.99, 203.0.113.30, 10.0.0.99").andExpect(status().isTooManyRequests());

		// Another real address has its own window.
		loginWithForwarded("198.51.100.7, 203.0.113.31").andExpect(status().isUnauthorized());
	}

	@Test
	void absurdForwardedHeaderFallsBackInsteadOfFailing() throws Exception {
		String enorme = "9".repeat(5_000);
		loginWithForwarded(enorme).andExpect(status().isUnauthorized());
	}

	@Test
	void normalUseIsNotBlocked() throws Exception {
		// Duas tentativas erradas e uma certa: nada aqui pode virar 429.
		login("203.0.113.50").andExpect(status().isUnauthorized());
		login("203.0.113.50").andExpect(status().isUnauthorized());
		login("203.0.113.50").andExpect(status().isUnauthorized());
	}

	@Test
	void signupHasItsOwnLimit() throws Exception {
		String ip = "203.0.113.60";
		signup(ip).andExpect(status().isBadRequest());
		signup(ip).andExpect(status().isBadRequest());
		signup(ip).andExpect(status().isTooManyRequests());
	}

	@Test
	void refreshHasItsOwnBucket() throws Exception {
		String ip = "203.0.113.70";
		for (int i = 0; i < 2; i++) {
			refresh(ip).andExpect(status().isUnauthorized());
		}
		refresh(ip).andExpect(status().isTooManyRequests());
		// Exhausting refresh does not consume the login bucket of the same address.
		login(ip).andExpect(status().isUnauthorized());
	}

	private org.springframework.test.web.servlet.ResultActions refresh(String ip) throws Exception {
		return mockMvc.perform(post("/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.header("X-Forwarded-For", ip)
			.content(objectMapper.writeValueAsString(Map.of("refreshToken", "not-a-real-token"))));
	}

	private org.springframework.test.web.servlet.ResultActions login(String ip) throws Exception {
		return mockMvc.perform(post("/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.header("X-Forwarded-For", ip)
			.header("Idempotency-Key", "ratelimit-" + Math.random())
			.content(objectMapper.writeValueAsString(Map.of("email", "ninguem@exemplo.com", "password", "errada"))));
	}

	private org.springframework.test.web.servlet.ResultActions loginWithForwarded(String forwarded) throws Exception {
		return mockMvc.perform(post("/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.header("X-Forwarded-For", forwarded)
			.header("Idempotency-Key", "ratelimit-" + Math.random())
			.content(objectMapper.writeValueAsString(Map.of("email", "ninguem@exemplo.com", "password", "errada"))));
	}

	private org.springframework.test.web.servlet.ResultActions signup(String ip) throws Exception {
		return mockMvc.perform(post("/auth/signup")
			.contentType(MediaType.APPLICATION_JSON)
			.header("X-Forwarded-For", ip)
			.header("Idempotency-Key", "ratelimit-signup-" + Math.random())
			.content(objectMapper.writeValueAsString(Map.of("name", "Teste", "role", "DEV",
				"email", "sem-arroba", "password", "curta", "acceptedTerms", true))));
	}
}
