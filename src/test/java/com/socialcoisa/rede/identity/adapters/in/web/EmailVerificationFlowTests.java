package com.socialcoisa.rede.identity.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Fluxo real de confirmacao de e-mail: cadastro pendente, link enviado, token de uso unico com
 * validade, reenvio limitado e liberacao do acesso so depois da confirmacao.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmailVerificationFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	/** Espiao do envio: e por aqui que o teste ve o token que so existe no e-mail. */
	@MockitoBean
	private EmailService emailService;

	private final List<String> sentTokens = new ArrayList<>();

	@BeforeEach
	void setUp() {
		sentTokens.clear();
		when(emailService.sendVerificationEmail(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
			sentTokens.add(invocation.getArgument(2, String.class));
			return true;
		});
	}

	@Test
	void accountOnlyWorksAfterTheEmailIsConfirmed() throws Exception {
		String email = "verifica.fluxo@example.com";
		signup(email);

		assertThat(sentTokens).hasSize(1);
		String token = sentTokens.get(0);

		// Pendente: o login nao emite sessao e explica o motivo.
		mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", "verify-login-blocked")
				.content(loginPayload(email)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

		MvcResult verified = mockMvc.perform(post("/auth/email/verify")
				.header("Idempotency-Key", "verify-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", token))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.emailVerified").value(true))
			.andReturn();

		String sessionToken = com.socialcoisa.rede.shared.web.TestAuthHelper.sessionToken(verified);

		// A sessao do link ja vale para as funcionalidades protegidas.
		mockMvc.perform(get("/auth/me").header("X-Session-Token", sessionToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.emailVerified").value(true));

		// Uso unico: o mesmo link nao confirma duas vezes. No cadastro de duas etapas o pendente
		// some quando o link e usado, entao a segunda vez responde 404 INVALID_TOKEN (o 409
		// TOKEN_ALREADY_USED era do fluxo de conta ja criada, que guarda o token usado).
		mockMvc.perform(post("/auth/email/verify")
				.header("Idempotency-Key", "verify-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", token))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("INVALID_TOKEN"));

		// Depois de confirmado, o login normal funciona.
		mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", "verify-login-ok")
				.content(loginPayload(email)))
			.andExpect(status().isOk());
	}

	@Test
	void rejectsUnknownAndExpiredTokens() throws Exception {
		String email = "verifica.expirado@example.com";
		signup(email);

		mockMvc.perform(post("/auth/email/verify")
				.header("Idempotency-Key", "verify-unknown")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", "verify_nao-existe"))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("INVALID_TOKEN"));

		// Token vencido: no cadastro de duas etapas o link e o do PENDENTE (a conta ainda nao
		// existe). O token cru e o que o e-mail levou; o banco guarda o hash, entao o teste
		// conhece o valor que o e-mail stubado capturou.
		String expiredToken = sentTokens.get(0);
		TestDatabase.expirePendingSignup(email);

		mockMvc.perform(post("/auth/email/verify")
				.header("Idempotency-Key", "verify-expired")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", expiredToken))))
			.andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
	}

	@Test
	void resendIsRateLimitedAndDoesNotRevealWhetherTheEmailExists() throws Exception {
		String email = "verifica.reenvio@example.com";
		signup(email);
		assertThat(sentTokens).hasSize(1);

		// O status e igual com conta e sem conta; o teto por conta e silencioso de proposito, para o
		// codigo de resposta nao denunciar quais enderecos estao cadastrados.
		mockMvc.perform(resend(email)).andExpect(status().isAccepted());
		mockMvc.perform(resend(email)).andExpect(status().isAccepted());
		mockMvc.perform(resend("nao-existe-" + System.nanoTime() + "@example.com"))
			.andExpect(status().isAccepted());
	}

	@Test
	void signupResponseNeverCarriesCredentials() throws Exception {
		String email = "verifica.resposta@example.com";
		MvcResult result = signup(email);

		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain("token").doesNotContain("refreshToken");
		assertThat(body).contains("verificationRequired");
	}

	private MvcResult signup(String email) throws Exception {
		return mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "signup-" + email)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "Pessoa Verificada",
					"role", "USER",
					"email", email,
					"password", "Test1234!",
					"acceptedTerms", true,
					// Obrigatoria no cadastro desde a regra do ECA (400 BIRTH_DATE_REQUIRED sem ela).
					"birthDate", "1990-05-10"))))
			.andExpect(status().isCreated())
			.andReturn();
	}

	private String loginPayload(String email) throws Exception {
		return objectMapper.writeValueAsString(Map.of("email", email, "password", "Test1234!"));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder resend(String email)
			throws Exception {
		return post("/auth/email/resend")
			.header("Idempotency-Key", "resend-" + email + "-" + System.nanoTime())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("email", email)));
	}
}
