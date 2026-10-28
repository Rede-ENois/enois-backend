package com.socialcoisa.rede.identity.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Trocar a senha ENCERRA a sessão — e isso tem um efeito colateral no fluxo do E2E.
 *
 * O banner de ativação da criptografia ponta a ponta (front) tem dois caminhos:
 * <ul>
 *   <li>conta COM senha: pede a senha, decifra/guarda o keypair — não troca senha;</li>
 *   <li>conta SEM senha (login social): define a senha E guarda o keypair.</li>
 * </ul>
 *
 * No segundo caminho a ordem importava: o front trocava a senha e só DEPOIS subia o keypair
 * (`POST /e2e/keypair`). Mas a troca de senha revoga todas as sessões do usuário — inclusive a
 * que está em uso. O upload do keypair saía então com a sessão morta: 401, o banner caía em
 * "Não foi possível definir a senha" e a pessoa ficava sem chave, sem erro claro e com a
 * sessão encerrada.
 *
 * Este teste trava o INVARIANTE de que a troca derruba a sessão (a causa), para que o front
 * não volte a depender de upload depois da troca.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordChangeEndsSessionTests {

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

	@Test
	void trocarASenhaEncerraASessaoQueEstaEmUso() throws Exception {
		var session = authHelper().authenticate("sessao-derrubada-pela-senha");

		mockMvc.perform(post("/auth/password/change")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(changeBody("Test1234!", "NovaSenha123")))
			.andExpect(status().isOk());

		// A sessão que fez a troca não vale mais: é o que faz o upload do keypair (que vem
		// depois) sair com 401.
		mockMvc.perform(get("/auth/me").header("X-Session-Token", session.token()))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void oUploadDoKeypairE2eSoFuncionaComSessaoViva() throws Exception {
		var session = authHelper().authenticate("e2e-upload-sem-sessao");

		// Com a sessão viva, o upload passa.
		mockMvc.perform(post("/e2e/keypair")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"publicKey", "chave-publica",
					"encryptedPrivateKey", "chave-privada-cifrada",
					"algorithm", "RSA-OAEP"))))
			.andExpect(status().isOk());

		// Troca a senha: a sessão morre.
		mockMvc.perform(post("/auth/password/change")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(changeBody("Test1234!", "NovaSenha123")))
			.andExpect(status().isOk());

		// O mesmo upload, agora, é recusado — é exatamente o que acontecia no banner do E2E.
		mockMvc.perform(post("/e2e/keypair")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"publicKey", "chave-publica-2",
					"encryptedPrivateKey", "chave-privada-cifrada-2",
					"algorithm", "RSA-OAEP"))))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void contaSemSenhaCriaAprimeiraEASessaoMorre() throws Exception {
		var session = authHelper().authenticate("e2e-conta-social-sem-senha");
		TestDatabase.makePasswordUnusable(session.userId());

		mockMvc.perform(post("/auth/password/change")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(changeBody("", "NovaSenha123")))
			.andExpect(status().isOk());

		// A conta social que cria a primeira senha também perde a sessão: o front precisa
		// guardar o keypair ANTES da troca (ou reentrar depois).
		mockMvc.perform(get("/auth/me").header("X-Session-Token", session.token()))
			.andExpect(status().isUnauthorized());

		// E a senha criada é de verdade (a pessoa entra com ela).
		mockMvc.perform(post("/auth/login")
				.header("Idempotency-Key", "login-e2e-conta-social-sem-senha-criada")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"email", "e2e-conta-social-sem-senha@test.com",
					"password", "NovaSenha123"))))
			.andExpect(status().isOk());
	}
}
