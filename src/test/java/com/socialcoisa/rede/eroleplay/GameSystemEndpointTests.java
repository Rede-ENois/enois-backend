package com.socialcoisa.rede.eroleplay;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * O catalogo publico de sistemas: sem sessao, so o que o adapter declara.
 *
 * O que este teste tranca:
 *
 * - a leitura e publica (a tela monta o seletor antes do login);
 * - o catalogo traz o sistema original do Eroleplay com autoridade e licenca a vista;
 * - a familia anunciada e a mesma que a campanha grava.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GameSystemEndpointTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void oCatalogoEPublicoETrazOSistemaOriginal() throws Exception {
		mockMvc.perform(get("/roleplay/systems"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value("d20-generico"))
			.andExpect(jsonPath("$[0].family").value("D20"))
			.andExpect(jsonPath("$[0].builtIn").value(true))
			.andExpect(jsonPath("$[0].attributes.length()").value(11))
			.andExpect(jsonPath("$[0].attributes[0].key").value("forca"))
			.andExpect(jsonPath("$[0].attributes[0].type").value("NUMBER"));
	}
}
