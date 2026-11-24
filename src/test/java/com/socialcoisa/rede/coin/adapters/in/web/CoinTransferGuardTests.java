package com.socialcoisa.rede.coin.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Defesas da transferência depois do incidente de 22/10 (141 POSTs em rajada, cara de gasto
 * duplo): o MESMO Pix repetido em segundos é um Pix só, e uma conta não manda transferência
 * sem parar (teto por minuto/hora).
 *
 * O limite vem baixo neste contexto para o teste caber em segundos; o valor de produção é
 * rede.coin.transfer-per-minute (10) e ...-per-hour (60).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("critico")
class CoinTransferGuardTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("rede.coin.transfer-per-minute", () -> "3");
		registry.add("rede.coin.transfer-per-hour", () -> "30");
	}

	@Test
	void repeatedPixWithinTheWindowIsSentOnce() throws Exception {
		var sender = auth("guard_sender");
		var receiver = auth("guard_receiver");

		// Abre a carteira para o genesis (100) + presença (5) acontecerem antes das contas.
		mockMvc.perform(get("/carteira").header("X-Session-Token", sender.token())).andExpect(status().isOk());
		mockMvc.perform(get("/carteira").header("X-Session-Token", receiver.token())).andExpect(status().isOk());

		MvcResult first = transfer(sender.token(), "guard-receiver", 10_000, "mesmo pix");
		MvcResult second = transfer(sender.token(), "guard-receiver", 10_000, "mesmo pix");

		String firstRef = objectMapper.readTree(first.getResponse().getContentAsString()).get("refId").asText();
		String secondRef = objectMapper.readTree(second.getResponse().getContentAsString()).get("refId").asText();
		org.assertj.core.api.Assertions.assertThat(secondRef)
			.as("o segundo clique devolve a MESMA transferência").isEqualTo(firstRef);

		mockMvc.perform(get("/carteira").header("X-Session-Token", sender.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balance").value(95_000));
	}

	@Test
	void burstsOfTransfersAreThrottledBeforeTouchingTheLedger() throws Exception {
		var sender = auth("guard_burst_sender");
		var receiver = auth("guard_burst_receiver");
		mockMvc.perform(get("/carteira").header("X-Session-Token", sender.token())).andExpect(status().isOk());
		mockMvc.perform(get("/carteira").header("X-Session-Token", receiver.token())).andExpect(status().isOk());

		// Limite do contexto: 3 por minuto — cada uma com valor diferente (a deduplicação não age).
		for (int i = 1; i <= 3; i++) {
			transfer(sender.token(), "guard-burst-receiver", 1_000L * i, "pix " + i);
		}
		mockMvc.perform(post("/carteira/transferencias")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "guard-burst-4")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"toUsername", "guard-burst-receiver", "amount", 4_000, "message", "pix 4"))))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("ACTION_THROTTLED"));
	}

	private MvcResult transfer(String token, String to, long amount, String message) throws Exception {
		return mockMvc.perform(post("/carteira/transferencias")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", "guard-" + amount + "-" + message.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"toUsername", to, "amount", amount, "message", message))))
			.andExpect(status().isOk())
			.andReturn();
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}
}
