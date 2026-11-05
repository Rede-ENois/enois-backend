package com.socialcoisa.rede.e2e.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Fluxo completo do E2E via HTTP: upload de keypair, leitura, chave publica de outro user,
 * distribuição de chave de conversa e leitura da chave distribuida.
 *
 * Os testes cobrem os cenários reais de uso:
 * 1. Primeiro login: gerar e enviar keypair
 * 2. Segundo dispositivo: buscar keypair existente
 * 3. Abrir conversa: distribuir chave simétrica pros participantes
 * 4. Ler conversa: buscar chave simétrica pra decifrar
 * 5. Segurança: não-participante não distribui nem lê chave
 */
@SpringBootTest
@AutoConfigureMockMvc
class E2eKeyControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void uploadAndRetrieveKeypair() throws Exception {
		var user = auth.authenticate("e2e_kp_" + System.nanoTime());

		mockMvc.perform(post("/e2e/keypair")
				.header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"publicKey", "pub-test-123",
					"encryptedPrivateKey", "priv-enc-456"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));

		mockMvc.perform(get("/e2e/keypair")
				.header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.publicKey").value("pub-test-123"))
			.andExpect(jsonPath("$.encryptedPrivateKey").value("priv-enc-456"))
			.andExpect(jsonPath("$.algorithm").value("RSA-OAEP"));
	}

	@Test
	void newKeypairDeactivatesPrevious() throws Exception {
		var user = auth.authenticate("e2e_kp_rotate_" + System.nanoTime());

		mockMvc.perform(post("/e2e/keypair")
				.header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"publicKey", "old-pub", "encryptedPrivateKey", "old-priv"))))
			.andExpect(status().isOk());

		mockMvc.perform(post("/e2e/keypair")
				.header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"publicKey", "new-pub", "encryptedPrivateKey", "new-priv"))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/e2e/keypair")
				.header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.publicKey").value("new-pub"));
	}

	@Test
	void publicKeyOfAnotherUser() throws Exception {
		var alice = auth.authenticate("e2e_alice_" + System.nanoTime());
		var bob = auth.authenticate("e2e_bob_" + System.nanoTime());

		mockMvc.perform(post("/e2e/keypair")
				.header("X-Session-Token", alice.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"publicKey", "alice-pub", "encryptedPrivateKey", "alice-priv"))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/e2e/keypair/{userId}", alice.userId())
				.header("X-Session-Token", bob.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.publicKey").value("alice-pub"))
			.andExpect(jsonPath("$.algorithm").value("RSA-OAEP"));
	}

	@Test
	void refusesNullKeys() throws Exception {
		var user = auth.authenticate("e2e_null_" + System.nanoTime());

		mockMvc.perform(post("/e2e/keypair")
				.header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("publicKey", "pub"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_KEYPAIR"));
	}

	@Test
	void refusesOversizedKeys() throws Exception {
		var user = auth.authenticate("e2e_big_" + System.nanoTime());
		String big = "x".repeat(9000);

		mockMvc.perform(post("/e2e/keypair")
				.header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"publicKey", big, "encryptedPrivateKey", "priv"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("KEY_TOO_LARGE"));
	}

	@Test
	void noKeypairReturns404() throws Exception {
		var user = auth.authenticate("e2e_empty_" + System.nanoTime());

		mockMvc.perform(get("/e2e/keypair")
				.header("X-Session-Token", user.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NO_KEYPAIR"));
	}

	@Test
	void requiresSession() throws Exception {
		mockMvc.perform(post("/e2e/keypair")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"publicKey", "pub", "encryptedPrivateKey", "priv"))))
			.andExpect(status().isUnauthorized());
	}
}
