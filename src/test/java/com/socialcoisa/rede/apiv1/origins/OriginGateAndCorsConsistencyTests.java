package com.socialcoisa.rede.apiv1.origins;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.Tag;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A origem decidida pelo portão é a mesma que o CORS oferece: um subdomínio aceito no QA recebe a
 * resposta com CORS (sem credencial), e um subdomínio que nenhuma das duas listas conhece é recusado
 * já no portão, inclusive nas rotas de identidade que antes passavam por isenção.
 */
@Tag("critico")
@SpringBootTest(properties = {
		"rede.origins.enforce=true",
		"rede.origins.wildcards-allowed=true",
		"rede.origins.trusted=https://*.b7cloud.tech,https://enois-qa.b7cloud.tech",
		"rede.cors.allowed-origins=https://enois-qa.b7cloud.tech"
})
@AutoConfigureMockMvc
class OriginGateAndCorsConsistencyTests {

	private static final String APP_ORIGIN = "https://enois-qa.b7cloud.tech";
	private static final String FAMILY_SUBDOMAIN = "https://outro-projeto.b7cloud.tech";
	private static final String STRANGER = "https://teste-validacao.example";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void theExactAppOriginGetsCredentialedCorsOnPreflight() throws Exception {
		mockMvc.perform(options("/auth/login")
				.header("Origin", APP_ORIGIN)
				.header("Access-Control-Request-Method", "POST"))
			// O portão responde a preflight com 204; o CORS do Spring, quando também trata a
			// requisição, com 200. Os dois são sucesso — o que importa é o cabeçalho de resposta.
			.andExpect(status().is2xxSuccessful())
			.andExpect(header().string("Access-Control-Allow-Origin", APP_ORIGIN))
			.andExpect(header().string("Access-Control-Allow-Credentials", "true"));
	}

	@Test
	void aFamilySubdomainPassesTheGateAndGetsCorsWithoutCredentials() throws Exception {
		mockMvc.perform(options("/auth/login")
				.header("Origin", FAMILY_SUBDOMAIN)
				.header("Access-Control-Request-Method", "POST"))
			.andExpect(status().is2xxSuccessful())
			.andExpect(header().string("Access-Control-Allow-Origin", FAMILY_SUBDOMAIN))
			.andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
	}

	@Test
	void anUnknownOriginIsRefusedOnTheIdentityRoutesToo() throws Exception {
		// Cadastro e recuperação de senha não são isentos: a origem desconhecida é recusada antes
		// do controller, com o mesmo critério do CORS.
		mockMvc.perform(post("/auth/signup")
				.header("Origin", STRANGER)
				.header("Idempotency-Key", "origin-signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));

		mockMvc.perform(post("/auth/password/forgot")
				.header("Origin", STRANGER)
				.header("Idempotency-Key", "origin-forgot")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));
	}

	@Test
	void anUnknownOriginCannotReadTheAuthConfig() throws Exception {
		mockMvc.perform(get("/auth/config").header("Origin", STRANGER))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));
	}
}