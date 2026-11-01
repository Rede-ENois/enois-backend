package com.socialcoisa.rede.push.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class PushControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void requiresSession() throws Exception {
		mockMvc.perform(post("/push/subscriptions").contentType(MediaType.APPLICATION_JSON)
				.content("{\"endpoint\":\"https://push.example/1\",\"keys\":{\"p256dh\":\"a\",\"auth\":\"b\"}}"))
			.andExpect(status().isUnauthorized());
	}


	@Test
	void endpointLongoDemaisRecusaCom400DizendoOCampo() throws Exception {
		// A coluna e `push_subscriptions.endpoint varchar(700)` (o numero cabe no limite de chave
		// do InnoDB sem prefixo). A entidade declarava 1000, entao um endpoint maior passava pela
		// API e quebrava no INSERT com "Data too long" — que vira 409 "conflita com o estado atual".
		var session = auth.authenticate("push_longo");
		String endpoint = "https://push.example/" + "e".repeat(700);

		String payload = objectMapper.writeValueAsString(
			Map.of("endpoint", endpoint, "keys", Map.of("p256dh", "p256dh-key", "auth", "auth-key")));

		mockMvc.perform(post("/push/subscriptions").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "push-sub-longo")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PUSH_SUBSCRIPTION"))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("longo demais")));
	}

	@Test
	void registersAndRemovesSubscription() throws Exception {
		var session = auth.authenticate("push_user");
		String endpoint = "https://push.example/subscription/push_user";

		String payload = objectMapper.writeValueAsString(
			Map.of("endpoint", endpoint, "keys", Map.of("p256dh", "p256dh-key", "auth", "auth-key")));

		mockMvc.perform(post("/push/subscriptions").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "push-sub-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isNotEmpty());

		// Registrar o mesmo dispositivo duas vezes nao deve duplicar.
		mockMvc.perform(post("/push/subscriptions").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "push-sub-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isCreated());

		mockMvc.perform(delete("/push/subscriptions").header("X-Session-Token", session.token())
				.param("endpoint", endpoint))
			.andExpect(status().isNoContent());
	}

	@Test
	void rejectsHttpEndpoint() throws Exception {
		var session = auth.authenticate("push_user_bad");
		String payload = objectMapper.writeValueAsString(
			Map.of("endpoint", "http://push.example/1", "keys", Map.of("p256dh", "a", "auth", "b")));

		mockMvc.perform(post("/push/subscriptions").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "push-sub-bad")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isBadRequest());
	}

	@Test
	void exposesConfig() throws Exception {
		mockMvc.perform(get("/push/config"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.enabled").isBoolean());
	}

	@Test
	void configHasNoKeyWhenVapidIsNotConfigured() throws Exception {
		// A suíte não define VAPID_PUBLIC_KEY/VAPID_PRIVATE_KEY: o push fica desligado e o app não
		// oferece o botão. Ligar é só preencher as duas variáveis do ambiente (ver PushConfigEnabledTests).
		String body = mockMvc.perform(get("/push/config"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.enabled").value(false))
			.andReturn().getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(body).doesNotContain("publicKey\":\"");
	}
}