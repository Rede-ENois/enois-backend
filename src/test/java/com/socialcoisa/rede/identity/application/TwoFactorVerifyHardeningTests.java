package com.socialcoisa.rede.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.identity.application.port.out.CredentialRepository;
import com.socialcoisa.rede.identity.application.port.out.TwoFactorRepository;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.identity.domain.TwoFactorChallenge;
import com.socialcoisa.rede.identity.domain.TwoFactorMethod;
import com.socialcoisa.rede.shared.security.SecretCipher;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Caminhos de erro do `/auth/2fa/verify` que antes subiam como 500.
 *
 * <p>Um 500 nesse endpoint significa "não sei o que aconteceu" para quem está tentando entrar — e a
 * pessoa fica sem saber se errou o código, se a conta travou ou se o site caiu. Cada estado
 * inesperado precisa de uma resposta explícita.</p>
 */
class TwoFactorVerifyHardeningTests {

	private static final Instant NOW = Instant.parse("2026-10-26T22:00:00Z");

	private final TwoFactorRepository repository = mock(TwoFactorRepository.class);
	private final UserProfileRepository profiles = mock(UserProfileRepository.class);
	private final CredentialRepository credentials = mock(CredentialRepository.class);
	private final PasswordHasher hasher = mock(PasswordHasher.class);
	private final SecretCipher cipher = mock(SecretCipher.class);
	private final EmailService email = mock(EmailService.class);
	private final AuthSessionRepository sessions = mock(AuthSessionRepository.class);

	private TwoFactorService service() {
		return new TwoFactorService(repository, profiles, credentials, hasher, cipher, email, sessions,
			Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static TwoFactorChallenge challenge(TwoFactorMethod method) {
		return TwoFactorChallenge.restore("challenge_1", "user_1", method, "hash", 0,
			NOW.plusSeconds(600), null, NOW);
	}

	@Test
	void desafioSemMetodoRespondeErroExplicitoEmVezDeNpe() {
		when(repository.findChallenge("challenge_1")).thenReturn(Optional.of(challenge(null)));
		when(repository.claimAttempt("challenge_1")).thenReturn(TwoFactorRepository.AttemptClaim.CLAIMED);

		assertThatThrownBy(() -> service().verifyChallenge("challenge_1", "123456"))
			.isInstanceOfSatisfying(ApiException.class, failure -> {
				assertThat(failure.status()).isEqualTo(409);
				assertThat(failure.code()).isEqualTo("TWO_FACTOR_CHALLENGE_INVALID");
			});
	}

	@Test
	void segredoTotpIlegivelRespondeErroExplicitoEmVezDe500() {
		when(repository.findChallenge("challenge_1"))
			.thenReturn(Optional.of(challenge(TwoFactorMethod.TOTP)));
		when(repository.claimAttempt("challenge_1")).thenReturn(TwoFactorRepository.AttemptClaim.CLAIMED);
		when(repository.findActiveSecret("user_1")).thenReturn(Optional.of("segredo-cifrado"));
		when(cipher.decrypt("segredo-cifrado")).thenThrow(new IllegalStateException("chave trocada"));

		assertThatThrownBy(() -> service().verifyChallenge("challenge_1", "123456"))
			.isInstanceOfSatisfying(ApiException.class, failure -> {
				assertThat(failure.status()).isEqualTo(409);
				assertThat(failure.code()).isEqualTo("TWO_FACTOR_SECRET_UNREADABLE");
			});
	}

	@Test
	void unreadableTotpSecretStillAcceptsARecoveryCode() {
		when(repository.findChallenge("challenge_1"))
			.thenReturn(Optional.of(challenge(TwoFactorMethod.TOTP)));
		when(repository.claimAttempt("challenge_1")).thenReturn(TwoFactorRepository.AttemptClaim.CLAIMED);
		when(repository.findActiveSecret("user_1")).thenReturn(Optional.of("segredo-cifrado"));
		when(cipher.decrypt("segredo-cifrado")).thenThrow(new IllegalStateException("chave trocada"));
		when(repository.consumeBackupCode(org.mockito.ArgumentMatchers.eq("user_1"),
				org.mockito.ArgumentMatchers.eq(com.socialcoisa.rede.shared.security.Hashes.sha256Hex("ABCD-1234")),
				org.mockito.ArgumentMatchers.eq(NOW)))
			.thenReturn(true);
		when(repository.consumeChallenge("challenge_1", NOW)).thenReturn(true);

		assertThat(service().verifyChallenge("challenge_1", " abcd-1234 ")).isEqualTo("user_1");
	}

	@Test
	void desafioSemSegredoAtivoNaoQuebraEContaComoCodigoErrado() {
		when(repository.findChallenge("challenge_1"))
			.thenReturn(Optional.of(challenge(TwoFactorMethod.TOTP)));
		when(repository.claimAttempt("challenge_1")).thenReturn(TwoFactorRepository.AttemptClaim.CLAIMED);
		when(repository.findActiveSecret("user_1")).thenReturn(Optional.empty());
		when(repository.consumeBackupCode(anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
			.thenReturn(false);

		assertThatThrownBy(() -> service().verifyChallenge("challenge_1", "123456"))
			.isInstanceOfSatisfying(ApiException.class, failure -> {
				assertThat(failure.status()).isEqualTo(400);
				assertThat(failure.code()).isEqualTo("INVALID_TWO_FACTOR_CODE");
			});
	}
}
