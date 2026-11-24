package com.socialcoisa.rede.identity.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Cadastro em duas etapas: o POST /auth/signup (com captcha) so guarda um pendente e manda o link;
 * a conta (users + credenciais) nasce APENAS quando o e-mail e confirmado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("critico")
class PendingSignupFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String signupPayload(String email) throws Exception {
		return objectMapper.writeValueAsString(Map.of(
			"name", "Cadastro Pendente",
			"email", email,
			"password", "senha-segura",
			"acceptedTerms", true,
			"birthDate", "1990-01-01"));
	}

	@Test
	void accountOnlyExistsAfterTheEmailIsConfirmed() throws Exception {
		String email = "pendente.flow@example.com";

		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "pending-signup-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(signupPayload(email)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.verificationRequired").value(true));

		// Nada de conta: so o pendente com o hash da senha.
		assertThat(TestDatabase.userIdByEmail(email)).isEmpty();
		assertThat(TestDatabase.pendingSignupsByEmail(email)).isEqualTo(1);

		// O teste conhece o token cru (no fluxo real ele so existe no e-mail).
		TestDatabase.setPendingSignupToken(email, "token-de-teste-pendente");

		String verifyBody = objectMapper.writeValueAsString(Map.of("token", "token-de-teste-pendente"));

		mockMvc.perform(post("/auth/email/verify")
				.header("Idempotency-Key", "pending-verify-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(verifyBody))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.token").doesNotExist())
			.andExpect(jsonPath("$.user.emailVerified").value(true));

		// Agora sim: a conta existe, ja verificada, e o pendente sumiu.
		assertThat(TestDatabase.userIdByEmail(email)).isPresent();
		assertThat(TestDatabase.pendingSignupsByEmail(email)).isZero();

		// Uso unico: o mesmo link nao cria outra conta.
		mockMvc.perform(post("/auth/email/verify")
				.header("Idempotency-Key", "pending-verify-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(verifyBody))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
	}

	@Test
	void expiredLinkDoesNotCreateTheAccount() throws Exception {
		String email = "pendente.expirado@example.com";

		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "pending-signup-exp-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(signupPayload(email)))
			.andExpect(status().isCreated());

		TestDatabase.setPendingSignupToken(email, "token-vencido-pendente");
		TestDatabase.expirePendingSignup(email);

		mockMvc.perform(post("/auth/email/verify")
				.header("Idempotency-Key", "pending-verify-expired")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", "token-vencido-pendente"))))
			.andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));

		assertThat(TestDatabase.userIdByEmail(email)).isEmpty();
	}

	@Test
	void signingUpAgainReplacesThePendingInsteadOfFailing() throws Exception {
		String email = "pendente.repetido@example.com";

		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "pending-again-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(signupPayload(email)))
			.andExpect(status().isCreated());

		TestDatabase.setPendingSignupToken(email, "token-antes-do-reenvio");

		// Refazer o cadastro com o mesmo e-mail NAO pode dar 409: substitui o pendente.
		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "pending-again-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(signupPayload(email)))
			.andExpect(status().isCreated());

		assertThat(TestDatabase.pendingSignupsByEmail(email)).isEqualTo(1);
		// O link anterior deixou de valer.
		mockMvc.perform(post("/auth/email/verify")
				.header("Idempotency-Key", "pending-again-verify-old")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", "token-antes-do-reenvio"))))
			.andExpect(status().isNotFound());
		assertThat(TestDatabase.userIdByEmail(email)).isEmpty();
	}

	@Test
	void signupForAnEmailThatAlreadyHasAnAccountIsRefused() throws Exception {
		String email = "pendente.ja-existe@example.com";

		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "pending-signup-dup-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(signupPayload(email)))
			.andExpect(status().isCreated());

		TestDatabase.setPendingSignupToken(email, "token-dup-1");
		mockMvc.perform(post("/auth/email/verify")
				.header("Idempotency-Key", "pending-verify-dup-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", "token-dup-1"))))
			.andExpect(status().isOk());

		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "pending-signup-dup-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(signupPayload(email)))
			// Duplicate answers like a new signup (it does not reveal that the e-mail exists).
			// emailSent reflects the real send of the owner notice, as it does for a new signup.
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.verificationRequired").value(true))
			.andExpect(jsonPath("$.emailSent").isBoolean());
	}
}
