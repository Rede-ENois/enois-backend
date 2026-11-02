package com.socialcoisa.rede.apiv1.origins;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Preflight responde sempre — inclusive de origem não cadastrada e em caminho que não existe.
 *
 * <p>O OPTIONS não executa nada nem devolve dado; a operação real continua barrada pelo gate de
 * origem. Recusar o preflight não protege nada e só troca a recusa por uma falha de CORS no
 * navegador, que aparece para a pessoa como "sem conexão" numa ação que nunca chegou ao servidor.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
	"rede.origins.enforce=true",
	"rede.cors.allowed-origins=https://enois.ia.br"
})
class OptionsAlwaysAllowedTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void unknownOriginGetsPreflightAnswered() throws Exception {
		mockMvc.perform(options("/feed/posts/x/reactions")
				.header("Origin", "https://exemplo-nao-cadastrado.test")
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "content-type,x-session-token,idempotency-key"))
			.andExpect(status().is2xxSuccessful())
			.andExpect(header().string("Access-Control-Allow-Origin", "https://exemplo-nao-cadastrado.test"))
			.andExpect(header().string("Access-Control-Allow-Methods",
				org.hamcrest.Matchers.containsString("OPTIONS")))
			.andExpect(header().string("Access-Control-Allow-Headers",
				org.hamcrest.Matchers.containsString("x-session-token")));
	}

	/**
	 * O front chama a API cross-origin com o cookie de sessao. Sem a credencial ecoada no preflight o
	 * navegador descarta a resposta ("must be 'true' when the request's credentials mode is
	 * 'include'") e a tela aparece vazia — foi o que quebrou a QA depois do resposta por cookie.
	 */
	@Test
	void trustedOriginPreflightCarriesCredentials() throws Exception {
		mockMvc.perform(options("/feed/posts/x/reactions")
				.header("Origin", "https://enois.ia.br")
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "content-type"))
			.andExpect(status().is2xxSuccessful())
			.andExpect(header().string("Access-Control-Allow-Origin", "https://enois.ia.br"))
			.andExpect(header().string("Access-Control-Allow-Credentials", "true"));
	}

	/** Origem de terceiro cadastrada (ou desconhecida) nao recebe credencial cross-origin. */
	@Test
	void unknownOriginPreflightDoesNotCarryCredentials() throws Exception {
		mockMvc.perform(options("/feed/posts/x/reactions")
				.header("Origin", "https://exemplo-nao-cadastrado.test")
				.header("Access-Control-Request-Method", "POST"))
			.andExpect(status().is2xxSuccessful())
			.andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
	}

	/**
	 * Subdominio que casa com o curinga confiavel passa o gate, mas NAO recebe credencial: um
	 * subdominio irmao comprometido nao pode chamar a API com o cookie da vitima.
	 */
	@Test
	void wildcardSubdomainPreflightDoesNotCarryCredentials() throws Exception {
		mockMvc.perform(options("/feed/posts/x/reactions")
				.header("Origin", "https://outro.b7cloud.tech")
				.header("Access-Control-Request-Method", "POST"))
			.andExpect(status().is2xxSuccessful())
			.andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
	}

	@Test
	void pathThatDoesNotExistAlsoGetsPreflightAnswered() throws Exception {
		mockMvc.perform(options("/caminho/que/nao/existe")
				.header("Origin", "https://enois.ia.br")
				.header("Access-Control-Request-Method", "POST"))
			.andExpect(status().is2xxSuccessful());
	}

	@Test
	void preflightWithoutOriginIsNotRejected() throws Exception {
		mockMvc.perform(options("/feed/posts/x/reactions")
				.header("Access-Control-Request-Method", "POST"))
			.andExpect(status().is2xxSuccessful());
	}
}
