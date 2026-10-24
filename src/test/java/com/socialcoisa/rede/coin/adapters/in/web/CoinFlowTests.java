package com.socialcoisa.rede.coin.adapters.in.web;

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
 * O fluxo da faísca: genesis idempotente (100 por conta), transferência P2P estilo Pix
 * (por username, irreversível), extrato dos dois lados na mesma operação, saldo
 * insuficiente recusado e teto de conta nova.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CoinFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void genesisAirdropTransferAndExtract() throws Exception {
		var sender = auth("coin_sender");
		var receiver = auth("coin_receiver");

		// Genesis: a carteira nasce com 100 faíscas; reabrir não duplica.
		MvcResult wallet1 = mockMvc.perform(get("/carteira").header("X-Session-Token", sender.token()))
			.andReturn();
		if (wallet1.getResponse().getStatus() != 200) {
			System.out.println("DEBUG carteira: " + wallet1.getResponse().getContentAsString());
		}
		org.assertj.core.api.Assertions.assertThat(wallet1.getResponse().getStatus())
			.as("carteira: " + wallet1.getResponse().getContentAsString()).isEqualTo(200);
		MvcResult wallet2 = mockMvc.perform(get("/carteira").header("X-Session-Token", sender.token()))
			.andReturn();
		if (wallet2.getResponse().getStatus() != 200) {
			System.out.println("DEBUG carteira2: " + wallet2.getResponse().getContentAsString());
		}
		org.assertj.core.api.Assertions.assertThat(wallet2.getResponse().getStatus())
			.as("carteira2: " + wallet2.getResponse().getContentAsString()).isEqualTo(200);
		org.assertj.core.api.Assertions.assertThat(
			objectMapper.readTree(wallet2.getResponse().getContentAsString()).get("balance").asLong())
			.isEqualTo(105_000); // 100 faíscas do airdrop + 5 da presença do dia, em milésimos

		// Transferência P2P por username (estilo Pix).
		mockMvc.perform(post("/carteira/transferencias")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "coin-p2p-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"toUsername", "coin-receiver", "amount", 30_000, "message", "pelo mockup lindo"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SENT"));

		// Saldo nos dois lados: 70 e 130.
		mockMvc.perform(get("/carteira").header("X-Session-Token", sender.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balance").value(75_000));
		mockMvc.perform(get("/carteira").header("X-Session-Token", receiver.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balance").value(135_000));

		// O extrato de cada um registra a MESMA transferência (a ordem dentro do mesmo
		// segundo não é determinística — filtra por tipo).
		mockMvc.perform(get("/carteira").header("X-Session-Token", sender.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entries[?(@.kind == 'TRANSFER_OUT')]").isNotEmpty());
		mockMvc.perform(get("/carteira").header("X-Session-Token", receiver.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entries[?(@.kind == 'TRANSFER_IN')]").isNotEmpty());

		// Saldo insuficiente recusa (valor dentro do teto de conta nova).
		mockMvc.perform(post("/carteira/transferencias")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "coin-p2p-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"toUsername", "coin-receiver", "amount", 400_000))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_SPARKS"));

		// Conta nova tem teto de envio diário: 999_999 mf (999,999 faíscas) estoura os 500/dia.
		mockMvc.perform(post("/carteira/transferencias")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "coin-p2p-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"toUsername", "coin-receiver", "amount", 999_999))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NEW_ACCOUNT_LIMIT"));

		// A mesma Idempotency-Key com o MESMO payload não duplica (replay do Pix).
		mockMvc.perform(post("/carteira/transferencias")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "coin-p2p-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"toUsername", "coin-receiver", "amount", 30_000, "message", "pelo mockup lindo"))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/carteira").header("X-Session-Token", sender.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balance").value(75_000));

		// Não transfere para si mesmo.
		mockMvc.perform(post("/carteira/transferencias")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "coin-p2p-self")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"toUsername", "coin-sender", "amount", 10))))
			.andExpect(status().isBadRequest());
	}

	/**
	 * Supply da moeda: o total do gênese é fixo e a soma de todas as contas tem que fechar com ele
	 * (nada cria faísca, nada some). A leitura é dado de tesouraria — só a administração vê.
	 */
	@Test
	void supplyIsClosedLoopAndAdminOnly() throws Exception {
		var user = auth("coin_supply_plain");
		var admin = auth("group-admin");

		// O gênese acontece no primeiro acesso à carteira; a airdrop só MOVE valor (cofre -> usuário).
		mockMvc.perform(get("/carteira").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/carteira").header("X-Session-Token", user.token()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/admin/carteira/supply").header("X-Session-Token", user.token()))
			.andExpect(status().isForbidden());

		// O H2 é compartilhado: não se afirma valor absoluto de circulação, afirma-se o INVARIANTE.
		long circulacaoAntes = leiaSupply(admin);

		// Transferência entre contas não muda o total nem a circulação: valor só se move.
		mockMvc.perform(post("/carteira/transferencias")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "coin-supply-transfer")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("toUsername", "coin-supply-plain", "amount", 500))))
			.andExpect(status().isOk());

		long circulacaoDepois = leiaSupply(admin);
		org.junit.jupiter.api.Assertions.assertEquals(circulacaoAntes, circulacaoDepois,
			"Transferência não pode mudar o que circula");
	}

	/** Lê o supply e afirma os invariantes: total fixo, nada criado, soma = circulação. */
	private long leiaSupply(TestAuthHelper.AuthSession admin) throws Exception {
		var resultado = mockMvc.perform(get("/admin/carteira/supply").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andReturn();
		var node = objectMapper.readTree(resultado.getResponse().getContentAsString());
		long total = node.get("total").asLong();
		long cofre = node.get("cofre").asLong();
		long circulacao = node.get("circulacao").asLong();
		long soma = node.get("somaSaldos").asLong();
		org.junit.jupiter.api.Assertions.assertEquals(2_000_000_000L, total, "Total do genesis é fixo");
		org.junit.jupiter.api.Assertions.assertTrue(node.get("coerente").asBoolean(),
			"Soma dos saldos tem que ser igual ao total (nada emite, nada desaparece)");
		org.junit.jupiter.api.Assertions.assertEquals(total, cofre + circulacao, "Cofre + circulação = total");
		org.junit.jupiter.api.Assertions.assertEquals(total, soma,
			"Soma de TODAS as contas (cofre incluído) = total do genesis");
		return circulacao;
	}

	/**
	 * Incidente 22/10: o atacante varreu /admin/carteira/disputas. A trava de /admin/** tem de
	 * responder 403 para quem não é admin (e 200 para quem é).
	 */
	@Test
	void adminWalletQueueIsClosedToNonAdmins() throws Exception {
		var user = auth("coin_plain_user");
		var admin = auth("group-admin");

		mockMvc.perform(get("/admin/carteira/disputas").header("X-Session-Token", user.token()))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/admin/carteira/disputas").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk());

		// Auditoria de incidente: o admin lê o extrato de uma conta (e o comum não).
		mockMvc.perform(get("/admin/carteira/usuarios/coin-plain-user/extrato")
				.header("X-Session-Token", user.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/carteira/usuarios/coin-plain-user/extrato")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("coin-plain-user"))
			.andExpect(jsonPath("$.balance").value(0));
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}
}
