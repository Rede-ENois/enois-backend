package com.socialcoisa.rede.identity.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import com.socialcoisa.rede.shared.security.Totp;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import tools.jackson.databind.ObjectMapper;

/**
 * Segundo fator de ponta a ponta: ativacao por aplicativo, login com desafio, codigo por e-mail,
 * codigo de recuperacao e a regra de credibilidade (sem verificacao, reputacao zerada).
 */
@Tag("critico")
@SpringBootTest
@AutoConfigureMockMvc
class TwoFactorFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	/** Espiao do envio: e por onde o teste ve o codigo que so existe no e-mail. */
	@MockitoBean
	private EmailService emailService;

	@Autowired
	private com.socialcoisa.rede.identity.application.port.out.TwoFactorRepository twoFactorRepository;

	private String lastEmailedCode;

	@BeforeEach
	void setUp() {
		lastEmailedCode = null;
		when(emailService.sendTwoFactorCode(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
			lastEmailedCode = invocation.getArgument(2, String.class);
			return true;
		});
		when(emailService.sendVerificationEmail(anyString(), anyString(), anyString())).thenReturn(true);
	}

	@Test
	void totpSetupRequiresAConfirmedCodeAndThenChallengesTheLogin() throws Exception {
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("two_factor_totp");

		// 1) setup devolve o segredo e a URI do aplicativo
		MvcResult setup = mockMvc.perform(post("/auth/2fa/totp/setup").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "totp-setup-1")
				.contentType(MediaType.APPLICATION_JSON).content(password("Test1234!")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.secret").isNotEmpty())
			.andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/")))
			.andReturn();
		String secret = objectMapper.readTree(setup.getResponse().getContentAsString()).get("secret").asText();

		// 2) codigo errado nao ativa
		mockMvc.perform(post("/auth/2fa/totp/enable").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "totp-enable-bad")
				.contentType(MediaType.APPLICATION_JSON)
				.content(codeWithPassword("000000", "Test1234!")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_TWO_FACTOR_CODE"));

		// 3) codigo do app ativa e devolve os codigos de recuperacao (mostrados uma vez)
		MvcResult enabled = mockMvc.perform(post("/auth/2fa/totp/enable").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "totp-enable-ok")
				.contentType(MediaType.APPLICATION_JSON)
				.content(codeWithPassword(validCode(secret), "Test1234!")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("TOTP_ENABLED"))
			.andExpect(jsonPath("$.backupCodes.length()").value(8))
			.andReturn();
		List<String> backupCodes = objectMapper.readTree(enabled.getResponse().getContentAsString())
			.get("backupCodes")
			.valueStream()
			.map(JsonNode::asText)
			.toList();

		mockMvc.perform(get("/auth/2fa/status").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totpEnabled").value(true))
			.andExpect(jsonPath("$.identityVerified").value(true))
			.andExpect(jsonPath("$.backupCodesLeft").value(8));

		// 4) o login por senha passa a exigir o segundo fator (nao vem token)
		MvcResult login = mockMvc.perform(post("/auth/login").header("Idempotency-Key", "totp-login-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginPayload("two_factor_totp@test.com")))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.twoFactorRequired").value(true))
			.andExpect(jsonPath("$.method").value("TOTP"))
			.andReturn();
		String challengeId = objectMapper.readTree(login.getResponse().getContentAsString()).get("challengeId").asText();

		// 5) codigo errado nao entrega sessao
		mockMvc.perform(post("/auth/2fa/verify").header("Idempotency-Key", "totp-verify-bad")
				.contentType(MediaType.APPLICATION_JSON).content(challenge(challengeId, "123456")))
			.andExpect(status().isBadRequest());

		// 6) codigo certo conclui o login (o do passo seguinte: o da ativacao ja foi gasto)
		MvcResult verified = mockMvc.perform(post("/auth/2fa/verify").header("Idempotency-Key", "totp-verify-ok")
				.contentType(MediaType.APPLICATION_JSON).content(challenge(challengeId, nextCode(secret))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.token").doesNotExist())
			.andExpect(jsonPath("$.user.twoFactorEnabled").value(true))
			.andReturn();

		// 7) o desafio e de uso unico
		mockMvc.perform(post("/auth/2fa/verify").header("Idempotency-Key", "totp-verify-replay")
				.contentType(MediaType.APPLICATION_JSON).content(challenge(challengeId, validCode(secret))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TWO_FACTOR_CHALLENGE_ALREADY_USED"));

		// 8) codigo de recuperacao tambem entra (e vale uma vez so)
		String sessionToken = TestAuthHelper.sessionToken(verified);
		assertThat(sessionToken).isNotBlank();

		MvcResult recoveryLogin = mockMvc.perform(post("/auth/login").header("Idempotency-Key", "totp-login-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginPayload("two_factor_totp@test.com")))
			.andExpect(status().isAccepted())
			.andReturn();
		String recoveryChallenge = objectMapper.readTree(recoveryLogin.getResponse().getContentAsString())
			.get("challengeId").asText();

		mockMvc.perform(post("/auth/2fa/verify").header("Idempotency-Key", "totp-verify-recovery")
				.contentType(MediaType.APPLICATION_JSON).content(challenge(recoveryChallenge, backupCodes.get(0))))
			.andExpect(status().isOk())
			.andExpect(cookie().exists("enois_session"));

		mockMvc.perform(get("/auth/2fa/status").header("X-Session-Token", sessionToken))
			.andExpect(jsonPath("$.backupCodesLeft").value(7));
	}

	@Test
	void emailSecondFactorSendsAndChecksTheCode() throws Exception {
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("two_factor_email");

		mockMvc.perform(post("/auth/2fa/email/request").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "email-2fa-1"))
			.andExpect(status().isAccepted());
		assertThat(lastEmailedCode).isNotNull();

		mockMvc.perform(post("/auth/2fa/email/confirm").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "email-2fa-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(code(lastEmailedCode)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("EMAIL_OTP_ENABLED"));

		// Login: a senha leva ao desafio por e-mail, com o codigo enviado na hora.
		MvcResult login = mockMvc.perform(post("/auth/login").header("Idempotency-Key", "email-login-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginPayload("two_factor_email@test.com")))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.method").value("EMAIL"))
			.andReturn();
		String challengeId = objectMapper.readTree(login.getResponse().getContentAsString()).get("challengeId").asText();
		assertThat(lastEmailedCode).isNotNull();

		mockMvc.perform(post("/auth/2fa/verify").header("Idempotency-Key", "email-verify-1")
				.contentType(MediaType.APPLICATION_JSON).content(challenge(challengeId, lastEmailedCode)))
			.andExpect(status().isOk())
			.andExpect(cookie().exists("enois_session"));
	}

	@Test
	void disablingRequiresThePasswordAndReactivatesTheChallenge() throws Exception {
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("two_factor_disable");

		MvcResult setup = mockMvc.perform(post("/auth/2fa/totp/setup").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "disable-setup")
				.contentType(MediaType.APPLICATION_JSON).content(password("Test1234!")))
			.andExpect(status().isOk())
			.andReturn();
		String secret = objectMapper.readTree(setup.getResponse().getContentAsString()).get("secret").asText();
		mockMvc.perform(post("/auth/2fa/totp/enable").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "disable-enable")
				.contentType(MediaType.APPLICATION_JSON).content(codeWithPassword(validCode(secret), "Test1234!")))
			.andExpect(status().isOk());

		// Sem a senha, ninguem desliga o segundo fator.
		mockMvc.perform(post("/auth/2fa/totp/disable").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "disable-bad-password")
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("password", "errada"))))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/auth/2fa/totp/disable").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "disable-ok")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("password", "Test1234!"))))
			.andExpect(status().isOk());

		// Desligado: o login volta a entregar sessao direto.
		mockMvc.perform(post("/auth/login").header("Idempotency-Key", "disable-login")
				.contentType(MediaType.APPLICATION_JSON).content(loginPayload("two_factor_disable@test.com")))
			.andExpect(status().isOk())
			.andExpect(cookie().exists("enois_session"));
	}

	@Test
	void accountLosesAccessToProtectedFeaturesWhenVerificationIsGone() throws Exception {
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("two_factor_reputation");

		// Conta confirmada: /auth/me mostra o estado e as areas protegidas respondem normalmente.
		JsonNode verified = objectMapper.readTree(mockMvc.perform(get("/auth/me")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString());
		assertThat(verified.get("identityVerified").asBoolean()).isTrue();
		mockMvc.perform(get("/notifications").header("X-Session-Token", session.token()))
			.andExpect(status().isOk());

		// Voltando ao estado pendente (sem e-mail confirmado, sem 2FA), a segunda barreira do
		// CurrentUserResolver barra as funcionalidades protegidas — /auth/me segue acessivel porque e
		// onde a propria pessoa ve o estado da conta.
		com.socialcoisa.rede.shared.web.TestDatabase.clearEmailVerification(session.userId());

		mockMvc.perform(get("/notifications").header("X-Session-Token", session.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

		JsonNode unverified = objectMapper.readTree(mockMvc.perform(get("/auth/me")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString());
		assertThat(unverified.get("identityVerified").asBoolean()).isFalse();

		// Ativando o segundo fator, a conta volta a ser considerada verificada.
		com.socialcoisa.rede.shared.web.TestDatabase.enableTwoFactor(session.userId(), Instant.now());
		mockMvc.perform(get("/notifications").header("X-Session-Token", session.token()))
			.andExpect(status().isOk());
	}

	@Test
	void totpSetupAndEnableRequireThePasswordAndRevokeOtherSessions() throws Exception {
		TestAuthHelper helper = new TestAuthHelper(mockMvc, objectMapper);
		var session = helper.authenticate("two_factor_reauth");
		var otherDevice = helper.authenticate("two_factor_reauth");

		mockMvc.perform(post("/auth/2fa/totp/setup").header("X-Session-Token", session.token()))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/auth/2fa/totp/setup").header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON).content(password("errada")))
			.andExpect(status().isUnauthorized());
		String secret = enrollSecret(session.token());

		mockMvc.perform(post("/auth/2fa/totp/enable").header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON).content(code(validCode(secret))))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/auth/2fa/totp/enable").header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON).content(codeWithPassword(validCode(secret), "Test1234!")))
			.andExpect(status().isOk());

		// The device that made the change stays signed in; every other session is gone.
		mockMvc.perform(get("/auth/me").header("X-Session-Token", session.token())).andExpect(status().isOk());
		mockMvc.perform(get("/auth/me").header("X-Session-Token", otherDevice.token()))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void wrongCodesLockTheChallengeEvenForTheRightCode() throws Exception {
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("two_factor_lockout");
		String secret = enrollSecret(session.token());
		mockMvc.perform(post("/auth/2fa/totp/enable").header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON).content(codeWithPassword(validCode(secret), "Test1234!")))
			.andExpect(status().isOk());
		String challengeId = loginChallenge("two_factor_lockout@test.com");

		for (int attempt = 0; attempt < com.socialcoisa.rede.identity.domain.TwoFactorChallenge.MAX_ATTEMPTS; attempt++) {
			mockMvc.perform(post("/auth/2fa/verify").contentType(MediaType.APPLICATION_JSON)
					.content(challenge(challengeId, wrongCode(secret))))
				.andExpect(status().isBadRequest());
		}
		mockMvc.perform(post("/auth/2fa/verify").contentType(MediaType.APPLICATION_JSON)
				.content(challenge(challengeId, nextCode(secret))))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
	}

	@Test
	void aTotpCodeIsAcceptedOnlyOnce() throws Exception {
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("two_factor_replay");
		String secret = enrollSecret(session.token());
		String enrollmentCode = validCode(secret);
		mockMvc.perform(post("/auth/2fa/totp/enable").header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON).content(codeWithPassword(enrollmentCode, "Test1234!")))
			.andExpect(status().isOk());

		// The enrollment code cannot complete a login.
		mockMvc.perform(post("/auth/2fa/verify").contentType(MediaType.APPLICATION_JSON)
				.content(challenge(loginChallenge("two_factor_replay@test.com"), enrollmentCode)))
			.andExpect(status().isBadRequest());

		String code = nextCode(secret);
		mockMvc.perform(post("/auth/2fa/verify").contentType(MediaType.APPLICATION_JSON)
				.content(challenge(loginChallenge("two_factor_replay@test.com"), code)))
			.andExpect(status().isOk());
		// Same code on a fresh challenge: rejected.
		mockMvc.perform(post("/auth/2fa/verify").contentType(MediaType.APPLICATION_JSON)
				.content(challenge(loginChallenge("two_factor_replay@test.com"), code)))
			.andExpect(status().isBadRequest());
	}

	@Test
	void wrongEmailSetupCodesAreCountedDespiteTheRollback() throws Exception {
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("two_factor_email_lockout");
		mockMvc.perform(post("/auth/2fa/email/request").header("X-Session-Token", session.token()))
			.andExpect(status().isAccepted());
		String wrong = "000000".equals(lastEmailedCode) ? "111111" : "000000";
		for (int attempt = 0; attempt < com.socialcoisa.rede.identity.domain.TwoFactorChallenge.MAX_ATTEMPTS; attempt++) {
			mockMvc.perform(post("/auth/2fa/email/confirm").header("X-Session-Token", session.token())
					.contentType(MediaType.APPLICATION_JSON).content(code(wrong)))
				.andExpect(status().isBadRequest());
		}
		mockMvc.perform(post("/auth/2fa/email/confirm").header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON).content(code(lastEmailedCode)))
			.andExpect(status().isTooManyRequests());
	}

	@Test
	void parallelCorrectCodesCompleteTheLoginOnlyOnce() throws Exception {
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("two_factor_email_race");
		mockMvc.perform(post("/auth/2fa/email/request").header("X-Session-Token", session.token()))
			.andExpect(status().isAccepted());
		mockMvc.perform(post("/auth/2fa/email/confirm").header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON).content(code(lastEmailedCode)))
			.andExpect(status().isOk());
		String challengeId = loginChallenge("two_factor_email_race@test.com");

		int threads = 4;
		var start = new java.util.concurrent.CountDownLatch(1);
		try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
			List<java.util.concurrent.Future<MvcResult>> results = new java.util.ArrayList<>();
			for (int index = 0; index < threads; index++) {
				// Distinct bodies (the code is trimmed) so the duplicate-POST filter lets every one through.
				String body = challenge(challengeId, " ".repeat(index) + lastEmailedCode);
				results.add(pool.submit(() -> {
					start.await();
					return mockMvc.perform(post("/auth/2fa/verify").contentType(MediaType.APPLICATION_JSON)
						.content(body)).andReturn();
				}));
			}
			start.countDown();
			int sessions = 0;
			for (var result : results) {
				MvcResult done = result.get();
				if (done.getResponse().getStatus() == 200) {
					sessions++;
					assertThat(done.getResponse().getCookie("enois_session")).isNotNull();
				}
				else {
					assertThat(done.getResponse().getStatus()).isEqualTo(409);
				}
			}
			assertThat(sessions).isEqualTo(1);
		}
	}

	@Test
	void aResendNeverReopensAConsumedChallenge() {
		String challengeId = "resend-race-" + java.util.UUID.randomUUID();
		Instant now = Instant.now();
		twoFactorRepository.saveChallenge(com.socialcoisa.rede.identity.domain.TwoFactorChallenge.create(challengeId,
			"resend-race-user", com.socialcoisa.rede.identity.domain.TwoFactorMethod.EMAIL, "hash",
			now.plusSeconds(300), now));
		assertThat(twoFactorRepository.consumeChallenge(challengeId, now)).isTrue();

		// The resend read the challenge before the verify consumed it; its write must not undo that.
		assertThat(twoFactorRepository.replaceCode(challengeId, "new-hash", now.plusSeconds(300))).isFalse();
		assertThat(twoFactorRepository.findChallenge(challengeId).orElseThrow().isConsumed()).isTrue();
		assertThat(twoFactorRepository.consumeChallenge(challengeId, now)).isFalse();
		// A claim on it says why it failed, without relying on the caller's snapshot.
		assertThat(twoFactorRepository.claimAttempt(challengeId))
			.isEqualTo(com.socialcoisa.rede.identity.application.port.out.TwoFactorRepository.AttemptClaim.CONSUMED);
	}

	@Test
	void parallelVerifiesCannotShareOneReadOfTheAttemptCounter() throws Exception {
		String challengeId = "claim-race-" + java.util.UUID.randomUUID();
		Instant now = Instant.now();
		twoFactorRepository.saveChallenge(com.socialcoisa.rede.identity.domain.TwoFactorChallenge.create(challengeId,
			"claim-race-user", com.socialcoisa.rede.identity.domain.TwoFactorMethod.EMAIL, "hash",
			now.plusSeconds(300), now));

		int threads = 20;
		var start = new java.util.concurrent.CountDownLatch(1);
		try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
			List<java.util.concurrent.Future<Boolean>> claims = new java.util.ArrayList<>();
			for (int index = 0; index < threads; index++) {
				claims.add(pool.submit(() -> {
					start.await();
					return twoFactorRepository.claimAttempt(challengeId)
						== com.socialcoisa.rede.identity.application.port.out.TwoFactorRepository.AttemptClaim.CLAIMED;
				}));
			}
			start.countDown();
			int granted = 0;
			for (var claim : claims) {
				granted += claim.get() ? 1 : 0;
			}
			assertThat(granted).isEqualTo(com.socialcoisa.rede.identity.domain.TwoFactorChallenge.MAX_ATTEMPTS);
		}
	}

	private String enrollSecret(String token) throws Exception {
		MvcResult setup = mockMvc.perform(post("/auth/2fa/totp/setup").header("X-Session-Token", token)
				.contentType(MediaType.APPLICATION_JSON).content(password("Test1234!")))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(setup.getResponse().getContentAsString()).get("secret").asText();
	}

	private String loginChallenge(String email) throws Exception {
		MvcResult login = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginPayload(email)))
			.andExpect(status().isAccepted())
			.andReturn();
		return objectMapper.readTree(login.getResponse().getContentAsString()).get("challengeId").asText();
	}

	/** A six-digit code that matches no step inside the verification window. */
	private String wrongCode(String secret) {
		long step = Instant.now().getEpochSecond() / 30;
		java.util.Set<String> valid = java.util.Set.of(Totp.codeAt(secret, step - 1), Totp.codeAt(secret, step),
			Totp.codeAt(secret, step + 1), Totp.codeAt(secret, step + 2));
		for (int candidate = 0;; candidate++) {
			String code = String.format("%06d", candidate);
			if (!valid.contains(code)) {
				return code;
			}
		}
	}

	private String nextCode(String secret) {
		return Totp.codeAt(secret, Instant.now().getEpochSecond() / 30 + 1);
	}

	private String password(String value) throws Exception {
		return objectMapper.writeValueAsString(Map.of("password", value));
	}

	private String validCode(String secret) {
		return Totp.codeAt(secret, Instant.now().getEpochSecond() / 30);
	}

	private String code(String value) throws Exception {
		return objectMapper.writeValueAsString(Map.of("code", value));
	}

	private String codeWithPassword(String value, String password) throws Exception {
		return objectMapper.writeValueAsString(Map.of("code", value, "password", password));
	}

	private String challenge(String challengeId, String value) throws Exception {
		return objectMapper.writeValueAsString(Map.of("challengeId", challengeId, "code", value));
	}

	private String loginPayload(String email) throws Exception {
		return objectMapper.writeValueAsString(Map.of("email", email, "password", "Test1234!"));
	}
}