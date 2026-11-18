package com.socialcoisa.rede.coin.dispute;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * A contestação de transação (golpe): a vítima contesta DENTRO da janela, a disputa vira
 * denúncia SCAM na fila de moderação; deferida → a transferência é REVERTIDA (espelho no
 * ledger) e o golpista restrito; rejeitada → QUEM CONTOU é restrito.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CoinDisputeFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@org.springframework.test.context.DynamicPropertySource
	static void adminAllowlist(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "disp_admin@test.com");
	}

	@Test
	void contestUpholdReversesAndRejectPunishesContester() throws Exception {
		// O admin primeiro: o 4º signup na mesma JVM bate o rate-limiter de auth.
		String adminToken = adminToken();
		var victim = auth("disp_victim");
		var scammer = auth("disp_scammer");
		var contestador = auth("disp_contestador");

		// Carteiras (105.000 mf cada) e uma transferência de 20.000 (20 faíscas).
		openWallet(victim.token());
		openWallet(scammer.token());
		openWallet(contestador.token());
		MvcResult transfer = mockMvc.perform(post("/carteira/transferencias")
				.header("X-Session-Token", victim.token())
				.header("Idempotency-Key", "disp-p2p-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"toUsername", "disp-scammer", "amount", 20_000, "message", "compro o jogo"))))
			.andExpect(status().isOk())
			.andReturn();
		String ref = objectMapper.readTree(transfer.getResponse().getContentAsString())
			.get("refId").asText();

		// O extrato da vítima carrega o refId da transferência.
		MvcResult wallet = mockMvc.perform(get("/carteira").header("X-Session-Token", victim.token()))
			.andExpect(status().isOk())
			.andReturn();
		boolean refNoExtrato = false;
		for (JsonNode entry : objectMapper.readTree(wallet.getResponse().getContentAsString()).get("entries")) {
			if (ref.equals(entry.get("refId").asText())) {
				refNoExtrato = true;
			}
		}
		org.assertj.core.api.Assertions.assertThat(refNoExtrato).as("refId no extrato").isTrue();

		// A vítima contesta (golpe). A disputa nasce OPEN e vira denúncia na fila.
		MvcResult contested = mockMvc.perform(post("/carteira/transacoes/" + ref + "/contestar")
				.header("X-Session-Token", victim.token())
				.header("Idempotency-Key", "disp-contest-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"reason", "golpe", "description", "Paguei por um jogo e nunca recebi nada de volta."))))
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(contested.getResponse().getStatus()).isEqualTo(200);
		org.assertj.core.api.Assertions.assertThat(
			objectMapper.readTree(contested.getResponse().getContentAsString()).get("state").asText()).isEqualTo("OPEN");
		String disputeId = objectMapper.readTree(contested.getResponse().getContentAsString()).get("id").asText();

		// Contestar a MESMA transferência de novo recusa.
		mockMvc.perform(post("/carteira/transacoes/" + ref + "/contestar")
				.header("X-Session-Token", victim.token())
				.header("Idempotency-Key", "disp-contest-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", "golpe", "description", "Tentativa repetida da mesma contestação."))))
			.andExpect(status().isConflict());

		// A fila da moderação mostra a disputa.
		mockMvc.perform(get("/admin/carteira/disputas?limit=30").header("X-Session-Token", adminToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + disputeId + "')].state").value("OPEN"));

		// VERDADEIRA: reverte (vítima 85.000 -> 105.000; golpista 125.000 -> 105.000) e restringe o golpista.
		mockMvc.perform(post("/admin/carteira/disputas/" + disputeId + "/deferir")
				.header("X-Session-Token", adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("note", "golpe confirmado"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("UPHELD"));
		balance(victim.token(), 105_000);
		balance(scammer.token(), 105_000);
		// A sanção da denúncia vinculada caiu no golpista (a conta dele fica restrita).
		mockMvc.perform(get("/me/sanctions").header("X-Session-Token", scammer.token()))
			.andExpect(status().isOk());

		// FALSA: o contestador de má-fé é quem é restrito.
		MvcResult transfer2 = mockMvc.perform(post("/carteira/transferencias")
				.header("X-Session-Token", contestador.token())
				.header("Idempotency-Key", "disp-p2p-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"toUsername", "disp-scammer", "amount", 5_000, "message", "divida antiga"))))
			.andExpect(status().isOk())
			.andReturn();
		String ref2 = objectMapper.readTree(transfer2.getResponse().getContentAsString()).get("refId").asText();
		MvcResult contested2 = mockMvc.perform(post("/carteira/transacoes/" + ref2 + "/contestar")
				.header("X-Session-Token", contestador.token())
				.header("Idempotency-Key", "disp-contest-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"reason", "golpe", "description", "Contestando sem motivo, so para testar o sistema."))))
			.andExpect(status().isOk())
			.andReturn();
		String dispute2 = objectMapper.readTree(contested2.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/admin/carteira/disputas/" + dispute2 + "/rejeitar")
				.header("X-Session-Token", adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("note", "contester de má-fé"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("REJECTED"));
		mockMvc.perform(get("/me/sanctions").header("X-Session-Token", contestador.token()))
			.andExpect(status().isOk());
	}

	private void openWallet(String token) throws Exception {
		mockMvc.perform(get("/carteira").header("X-Session-Token", token)).andExpect(status().isOk());
	}

	private void balance(String token, long expected) throws Exception {
		MvcResult wallet = mockMvc.perform(get("/carteira").header("X-Session-Token", token)).andExpect(status().isOk())
			.andReturn();
		long balance = objectMapper.readTree(wallet.getResponse().getContentAsString()).get("balance").asLong();
		org.assertj.core.api.Assertions.assertThat(balance)
			.as("saldo: " + wallet.getResponse().getContentAsString()).isEqualTo(expected);
	}

	private String adminToken() throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate("disp_admin").token();
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}
}
