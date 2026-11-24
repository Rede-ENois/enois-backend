package com.socialcoisa.rede.identity.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Quem entrou por login social nunca teve senha — e precisa poder CRIAR a primeira.
 *
 * O caso que originou isto: a conta veio do Google, e o segundo fator responde "defina uma senha
 * (em Trocar senha) antes de mexer no segundo fator". Só que a troca exigia a senha ATUAL, que
 * essa conta nunca teve: o aviso mandava para uma porta trancada por dentro, e a pessoa ficava
 * presa — sem 2FA e sem como destravar.
 *
 * O que este teste tranca:
 *
 * - conta sem senha usável cria a primeira SEM informar a atual, e a senha criada é de verdade
 *   (entra com ela em seguida);
 * - conta COM senha continua exigindo a atual — vazia ou errada é recusada;
 * - a tela sabe a diferença: `/auth/me` diz `hasPassword`, e ele vira `true` depois de criar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("critico")
class FirstPasswordTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper authHelper() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	private String changeBody(String current, String nova) throws Exception {
		return objectMapper.writeValueAsString(Map.of("currentPassword", current, "newPassword", nova));
	}

	private String loginBody(String userId, String senha) throws Exception {
		return objectMapper.writeValueAsString(Map.of("email", userId + "@test.com", "password", senha));
	}

	@Test
	void contaDeLoginSocialCriaAPrimeiraSenhaSemInformarAAtual() throws Exception {
		var session = authHelper().authenticate("primeira-senha-social");
		TestDatabase.makePasswordUnusable(session.userId());

		mockMvc.perform(post("/auth/password/change")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(changeBody("", "NovaSenha123")))
			.andExpect(status().isOk());

		// A senha criada é de verdade: a conta entra com ela.
		mockMvc.perform(post("/auth/login")
				// Chave própria: reutilizar a do TestAuthHelper com outra senha dá 409 de payload.
				.header("Idempotency-Key", "login-primeira-senha-social-criada")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody("primeira-senha-social", "NovaSenha123")))
			.andExpect(status().isOk());
	}

	@Test
	void contaComSenhaContinuaExigindoAAtual() throws Exception {
		var session = authHelper().authenticate("senha-existente");

		mockMvc.perform(post("/auth/password/change")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(changeBody("", "NovaSenha123")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

		mockMvc.perform(post("/auth/password/change")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(changeBody("senha-errada", "NovaSenha123")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

		// E com a atual certa, troca.
		mockMvc.perform(post("/auth/password/change")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(changeBody("Test1234!", "NovaSenha123")))
			.andExpect(status().isOk());
	}

	@Test
	void aTelaSabeSeAContaTemSenha() throws Exception {
		var session = authHelper().authenticate("diz-se-tem-senha");
		TestDatabase.makePasswordUnusable(session.userId());

		mockMvc.perform(get("/auth/me").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hasPassword").value(false));

		mockMvc.perform(post("/auth/password/change")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(changeBody("", "NovaSenha123")))
			.andExpect(status().isOk());

		// A troca encerra as sessões (é a regra): a leitura seguinte usa uma sessão nova.
		MvcResult login = mockMvc.perform(post("/auth/login")
				.header("Idempotency-Key", "login-diz-se-tem-senha-criada")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody("diz-se-tem-senha", "NovaSenha123")))
			.andExpect(status().isOk())
			.andReturn();
		String token = com.socialcoisa.rede.shared.web.TestAuthHelper.sessionToken(login);

		MvcResult me = mockMvc.perform(get("/auth/me").header("X-Session-Token", token))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(objectMapper.readTree(me.getResponse().getContentAsString()).get("hasPassword").asBoolean())
			.as("depois de criar, a conta passa a ter senha")
			.isTrue();
	}
}
