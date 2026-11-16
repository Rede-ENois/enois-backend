package com.socialcoisa.rede.identity.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Desafio de cadastro quando o Turnstile não está configurado: pergunta emitida pelo servidor,
 * resposta conferida no backend, expiração e uso único. Não depende de serviço externo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "rede.security.signup-captcha-enabled=true")
class SignupChallengeTests {

	private static final Pattern SUM = Pattern.compile("(\\d+) \\+ (\\d+)");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void challengeIsRequiredAndCannotBeReused() throws Exception {
		mockMvc.perform(get("/auth/config"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.captchaMode").value("CHALLENGE"));

		// Sem resposta: barra antes de criar a conta.
		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "captcha-none")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(signup("cap_none"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SIGNUP_CAPTCHA_REQUIRED"));

		Challenge challenge = issue();
		// The token carries no answer-derived field: only issuedAt|expiresAt|nonce.
		String payload = new String(java.util.Base64.getUrlDecoder().decode(challenge.id().split("\\.")[0]),
			java.nio.charset.StandardCharsets.UTF_8);
		org.assertj.core.api.Assertions.assertThat(payload.split("\\|")).hasSize(3);
		Thread.sleep(3_200);
		Map<String, Object> wrong = signup("cap_wrong");
		wrong.put("challengeId", challenge.id());
		wrong.put("challengeAnswer", challenge.answer() + 1);
		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "captcha-none")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(wrong)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SIGNUP_CAPTCHA_INVALID"));

		Challenge valid = issue();
		Thread.sleep(3_200);
		Map<String, Object> accepted = signup("cap_ok");
		accepted.put("challengeId", valid.id());
		accepted.put("challengeAnswer", valid.answer());
		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "captcha-none")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(accepted)))
			.andExpect(status().isCreated());

		// O mesmo desafio não cria uma segunda conta.
		Map<String, Object> replay = signup("cap_replay");
		replay.put("challengeId", valid.id());
		replay.put("challengeAnswer", valid.answer());
		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "captcha-none")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(replay)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SIGNUP_CAPTCHA_REUSED"));
	}

	private Challenge issue() throws Exception {
		MvcResult result = mockMvc.perform(get("/auth/signup-challenge"))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		Matcher matcher = SUM.matcher(body.get("question").asText());
		if (!matcher.find()) {
			throw new IllegalStateException("pergunta sem soma: " + body);
		}
		return new Challenge(body.get("challengeId").asText(),
			Integer.parseInt(matcher.group(1)) + Integer.parseInt(matcher.group(2)));
	}

	private Map<String, Object> signup(String prefix) {
		Map<String, Object> body = new HashMap<>();
		body.put("name", prefix);
		body.put("role", "USER");
		body.put("email", prefix + "@test.com");
		body.put("password", "Test1234!");
		body.put("acceptedTerms", true);
		// Obrigatoria no cadastro desde a regra do ECA (400 BIRTH_DATE_REQUIRED sem ela).
		body.put("birthDate", "1990-05-10");
		return body;
	}

	private record Challenge(String id, int answer) {
	}
}
