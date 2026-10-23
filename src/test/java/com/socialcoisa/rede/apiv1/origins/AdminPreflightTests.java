package com.socialcoisa.rede.apiv1.origins;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * O preflight do painel de administracao.
 *
 * O navegador so manda o GET depois que o OPTIONS responde 2xx com os cabecalhos de CORS. Em
 * producao o OPTIONS de qualquer /admin devolvia 500, entao o painel nunca abria (virava
 * `NetworkError` no console).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminPreflightTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void preflightDeRotaAdminResponde2xxComCors() throws Exception {
		mockMvc.perform(options("/admin/appeals")
				.header("Origin", "https://enois.ia.br")
				.header("Access-Control-Request-Method", "GET")
				.header("Access-Control-Request-Headers", "x-client-origin"))
			.andExpect(status().is2xxSuccessful())
			.andExpect(header().string("Access-Control-Allow-Origin", "https://enois.ia.br"));
	}
}
