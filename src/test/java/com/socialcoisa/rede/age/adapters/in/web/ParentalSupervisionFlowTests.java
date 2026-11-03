package com.socialcoisa.rede.age.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * ECA Digital: menor sem responsável autorizado não escreve; o aceite exige um adulto com conta
 * própria; revogar volta a bloquear. Todas as decisões são do backend.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ParentalSupervisionFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	/** Cria a conta pelo fluxo real (cadastro pendente + confirmação) e devolve o token de sessão. */
	private String createUser(String name, String email, String birthDate) throws Exception {
		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "parental-signup-" + email)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", name,
					"email", email,
					"password", "senha-segura",
					"acceptedTerms", true,
					"birthDate", birthDate))))
			.andExpect(status().isCreated());

		String raw = "token-" + email;
		TestDatabase.setPendingSignupToken(email, raw);

		return com.socialcoisa.rede.shared.web.TestAuthHelper.sessionToken(mockMvc.perform(post("/auth/email/verify")
				.header("Idempotency-Key", "parental-verify-" + email)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", raw))))
			.andExpect(status().isOk())
			.andReturn());
	}

	private void publish(String sessionToken, String key) throws Exception {
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT",
					"content", "Oi, eu sou jovem e quero aprender com a comunidade."))))
			.andExpect(status().isCreated());
	}

	@Test
	void minorOnlyWritesAfterAnAdultAcceptsTheInvitation() throws Exception {
		String minorEmail = "jovem.supervisao@example.com";
		String guardianEmail = "responsavel.supervisao@example.com";

		String minorSession = createUser("Jovem Supervisionado", minorEmail, "2012-05-10");
		String guardianSession = createUser("Responsavel Adulto", guardianEmail, "1988-02-20");

		// 1. Menor sem responsável: escrita bloqueada com o código próprio.
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", minorSession)
				.header("Idempotency-Key", "parental-post-blocked")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT", "content", "Quero publicar."))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PARENTAL_CONSENT_REQUIRED"));

		// 2. O menor convida o responsável.
		mockMvc.perform(post("/age/parental/invite")
				.header("X-Session-Token", minorSession)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("email", guardianEmail))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"));

		TestDatabase.setParentalLinkToken(guardianEmail, "convite-parental-teste");

		// 3. O próprio menor não consegue aceitar (o aceite exige conta de adulto).
		mockMvc.perform(post("/age/parental/accept")
				.header("X-Session-Token", minorSession)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", "convite-parental-teste"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("GUARDIAN_MUST_BE_ADULT"));

		// 4. O adulto aceita.
		mockMvc.perform(post("/age/parental/accept")
				.header("X-Session-Token", guardianSession)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", "convite-parental-teste"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUPERVISION_ACCEPTED"));

		// 5. O menor agora publica.
		publish(minorSession, "parental-post-allowed");

		// 6. O responsável vê somente o próprio supervisionado.
		mockMvc.perform(get("/age/parental/mine").header("X-Session-Token", guardianSession))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].username").isString());

		mockMvc.perform(get("/age/parental/mine").header("X-Session-Token", minorSession))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));

		// 7. Revogar volta a bloquear a escrita.
		String minorUserId = TestDatabase.userIdByEmail(minorEmail).orElseThrow();
		mockMvc.perform(post("/age/parental/revoke")
				.header("X-Session-Token", guardianSession)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("minorUserId", minorUserId))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUPERVISION_REVOKED"));

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", minorSession)
				.header("Idempotency-Key", "parental-post-after-revoke")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT", "content", "Quero publicar de novo."))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PARENTAL_CONSENT_REQUIRED"));
	}

	@Test
	void adultCannotRevokeSomeoneElsesSupervision() throws Exception {
		String minorEmail = "jovem.idor@example.com";
		String guardianEmail = "responsavel.idor@example.com";
		String strangerEmail = "estranho.idor@example.com";

		String minorSession = createUser("Jovem IDOR", minorEmail, "2013-03-03");
		String guardianSession = createUser("Responsavel IDOR", guardianEmail, "1985-07-07");
		String strangerSession = createUser("Estranho IDOR", strangerEmail, "1990-01-01");

		mockMvc.perform(post("/age/parental/invite")
				.header("X-Session-Token", minorSession)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("email", guardianEmail))))
			.andExpect(status().isOk());
		TestDatabase.setParentalLinkToken(guardianEmail, "convite-idor-teste");

		mockMvc.perform(post("/age/parental/accept")
				.header("X-Session-Token", guardianSession)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", "convite-idor-teste"))))
			.andExpect(status().isOk());

		String minorUserId = TestDatabase.userIdByEmail(minorEmail).orElseThrow();

		// Outro adulto não pode revogar o vínculo alheio.
		mockMvc.perform(post("/age/parental/revoke")
				.header("X-Session-Token", strangerSession)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("minorUserId", minorUserId))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_YOUR_MINOR"));

		assertThat(TestDatabase.userIdByEmail(minorEmail)).isPresent();
	}
}
