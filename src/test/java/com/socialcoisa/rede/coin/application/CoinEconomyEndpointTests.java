package com.socialcoisa.rede.coin.application;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * O painel admin da economia: leitura das métricas e o botão de recalcular. A economia automática
 * fica DESLIGADA neste teste para o recálculo não publicar uma cotação global e contaminar as
 * outras classes (a H2 é compartilhada).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CoinEconomyEndpointTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@org.springframework.test.context.DynamicPropertySource
	static void properties(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "eco_admin@test.com");
		registry.add("rede.coin.economy.enabled", () -> "false");
	}

	@Test
	void leituraDasMetricasExigeAdmin() throws Exception {
		mockMvc.perform(get("/admin/carteira/economia"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void adminLeEconomiaERecalculaSemPublicarQuandoDesligado() throws Exception {
		String admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("eco_admin").token();

		mockMvc.perform(get("/admin/carteira/economia").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.janelaDias").value(30))
			.andExpect(jsonPath("$.cofre").exists())
			.andExpect(jsonPath("$.escassez").exists())
			.andExpect(jsonPath("$.indice").exists())
			.andExpect(jsonPath("$.cotacaoAtualMicros").value(10_000))
			.andExpect(jsonPath("$.cotacaoSugeridaMicros").exists())
			.andExpect(jsonPath("$.publicavelAgora").exists())
			.andExpect(jsonPath("$.proximaAvaliacaoEm").exists());

		mockMvc.perform(post("/admin/carteira/economia/recalcular").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.publicada").value(false))
			.andExpect(jsonPath("$.cotacaoAnteriorMicros").value(10_000))
			.andExpect(jsonPath("$.cotacaoAtualMicros").value(10_000))
			.andExpect(jsonPath("$.publicavelAgora").exists())
			.andExpect(jsonPath("$.proximaAvaliacaoEm").exists())
			.andExpect(jsonPath("$.retornoCofre").exists())
			.andExpect(jsonPath("$.escassez").exists());
	}
}