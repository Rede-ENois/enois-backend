package com.socialcoisa.rede.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O desafio do cadastro é de quem pediu: sessão e aparelho entram na assinatura, e o tempo mínimo
 * entre a pergunta e a resposta é o de um humano digitando.
 */
class SignupChallengeBindingTests {

	private static final Pattern SUM = Pattern.compile("(\\d+) \\+ (\\d+)");
	private static final String SECRET = Base64.getEncoder().encodeToString("chave-de-teste-com-32-bytes!!!!!".getBytes());

	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private long nowMillis = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli();

	/** Relógio que avança conforme o teste: o desafio nasce e é respondido alguns segundos depois. */
	private final Clock clock = new Clock() {
		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return Instant.ofEpochMilli(nowMillis);
		}
	};

	private SignupChallengeService service() {
		return new SignupChallengeService(clock, jdbc, true, SECRET);
	}

	private static int answer(String question) {
		Matcher matcher = SUM.matcher(question);
		if (!matcher.find()) {
			throw new IllegalStateException("pergunta sem soma: " + question);
		}
		return Integer.parseInt(matcher.group(1)) + Integer.parseInt(matcher.group(2));
	}

	private void sleep(long millis) {
		nowMillis += millis;
	}

	@Test
	void aChallengeResolvedTooFastIsRefused() {
		SignupChallengeService service = service();
		var challenge = service.issue("sess-1", "device-1");

		sleep(1_000L);

		assertThatThrownBy(() -> service.require(challenge.challengeId(), answer(challenge.question()), "sess-1",
			"device-1")).isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("SIGNUP_CAPTCHA_TOO_FAST"));
	}

	@Test
	void aHumanPaceIsAccepted() {
		SignupChallengeService service = service();
		var challenge = service.issue("sess-1", "device-1");

		sleep(SignupChallengeService.MIN_ELAPSED_MS);

		service.require(challenge.challengeId(), answer(challenge.question()), "sess-1", "device-1");
	}

	@Test
	void aChallengeIssuedForAnotherSessionDoesNotWork() {
		SignupChallengeService service = service();
		var challenge = service.issue("sess-1", "device-1");
		sleep(SignupChallengeService.MIN_ELAPSED_MS);

		assertThatThrownBy(() -> service.require(challenge.challengeId(), answer(challenge.question()), "sess-2",
			"device-1")).isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("SIGNUP_CAPTCHA_INVALID"));
	}

	@Test
	void aChallengeIssuedForAnotherDeviceDoesNotWork() {
		SignupChallengeService service = service();
		var challenge = service.issue("sess-1", "device-1");
		sleep(SignupChallengeService.MIN_ELAPSED_MS);

		assertThatThrownBy(() -> service.require(challenge.challengeId(), answer(challenge.question()), "sess-1",
			"device-2")).isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("SIGNUP_CAPTCHA_INVALID"));
	}

	@Test
	void aSignedChallengeCannotBeRelabelledForTheVisitor() {
		SignupChallengeService service = service();
		var challenge = service.issue(null, null);
		sleep(SignupChallengeService.MIN_ELAPSED_MS);
		int answer = answer(challenge.question());

		// Assinado para visitante (sem sessão): apresentá-lo com sessão não muda a assinatura.
		assertThatThrownBy(() -> service.require(challenge.challengeId(), answer, "sess-9", null))
			.isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("SIGNUP_CAPTCHA_INVALID"));
		service.require(challenge.challengeId(), answer, null, null);
	}

	@Test
	void theTokenCarriesNoAnswerDerivedContent() {
		SignupChallengeService service = service();
		var challenge = service.issue("sess-1", "device-1");

		String payload = new String(Base64.getUrlDecoder().decode(challenge.challengeId().split("\\.")[0]),
			StandardCharsets.UTF_8);

		assertThat(payload.split("\\|")).hasSize(3);
	}

	@Test
	void theNonceCannotBeUsedTwiceOnAnyPod() {
		SignupChallengeService service = service();
		var first = service.issue("sess-1", "device-1");
		sleep(SignupChallengeService.MIN_ELAPSED_MS);
		service.require(first.challengeId(), answer(first.question()), "sess-1", "device-1");
		var second = service.issue("sess-1", "device-1");
		sleep(SignupChallengeService.MIN_ELAPSED_MS);
		// O nonce só colide no INSERT; o DELETE de vencidos passa.
		when(jdbc.update(org.mockito.ArgumentMatchers.startsWith("insert"), org.mockito.ArgumentMatchers.any(Object[].class)))
			.thenThrow(new DuplicateKeyException("nonce repetido"));

		assertThatThrownBy(() -> service.require(second.challengeId(), answer(second.question()), "sess-1", "device-1"))
			.isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("SIGNUP_CAPTCHA_REUSED"));
	}

	@Test
	void anOversizedChallengeIdIsRefusedBeforeAnyBase64Decode() {
		SignupChallengeService service = service();
		String oversized = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[2_048]) + "." + "x";
		assertThat(oversized.length()).isGreaterThan(SignupChallengeService.MAX_CHALLENGE_ID_LENGTH);
		assertThatThrownBy(() -> service.require(oversized, 4, "sess-1", "device-1"))
			.isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("SIGNUP_CAPTCHA_INVALID"));
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {
		"nao!base64.url",          // '!' fora do alfabeto
		"abc.def.",                // três partes
		"..." ,                    // separador sem conteúdo
		"<x>.yz"                   // caracteres de HTML
	})
	void aChallengeIdOutsideBase64UrlAlphabetIsRefused(String challengeId) {
		SignupChallengeService service = service();
		assertThatThrownBy(() -> service.require(challengeId, 4, "sess-1", "device-1"))
			.isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("SIGNUP_CAPTCHA_INVALID"));
	}
}