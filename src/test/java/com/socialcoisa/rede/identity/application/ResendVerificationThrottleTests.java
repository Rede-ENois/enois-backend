package com.socialcoisa.rede.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.identity.application.port.out.CredentialRepository;
import com.socialcoisa.rede.identity.application.port.out.EmailVerificationTokenRepository;
import com.socialcoisa.rede.identity.application.port.out.PasswordResetTokenRepository;
import com.socialcoisa.rede.identity.application.port.out.PendingSignupRepository;
import com.socialcoisa.rede.identity.application.port.out.UserExternalIdentityRepository;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.security.IpRateLimiter;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O reenvio de confirmacao nao pode dizer se um e-mail tem conta.
 *
 * <p>O teto visivel e por endereco, aplicado antes de qualquer consulta: quem existe e quem nao
 * existe recebem o mesmo 429 no mesmo numero de tentativas. O teto por conta continua valendo, mas
 * silencioso — quando ele respondia 429, o codigo de status denunciava o cadastro sem senha.</p>
 */
class ResendVerificationThrottleTests {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC);
	private static final int VISIBLE_LIMIT = 3;
	private static final String EMAIL = "reenvio@exemplo.invalid";

	private final EmailService email = mock(EmailService.class);
	private final EmailVerificationTokenRepository tokens = mock(EmailVerificationTokenRepository.class);
	private final PendingSignupRepository pending = mock(PendingSignupRepository.class);
	private final UserProfileRepository profiles = mock(UserProfileRepository.class);

	private AuthService service() {
		IpRateLimiter limiter = new IpRateLimiter(CLOCK,
			0, 0, 0, 0, 0, 0, VISIBLE_LIMIT, 15, 0, 0, 0, 0, 0, 0);
		return new AuthService(60, 30, profiles, mock(CredentialRepository.class), mock(AuthSessionRepository.class),
			mock(PasswordResetTokenRepository.class), tokens, pending, mock(TwoFactorService.class),
			mock(UserExternalIdentityRepository.class), mock(PasswordHasher.class), email, CLOCK,
			mock(SanctionGateReader.class), mock(JdbcTemplate.class), limiter);
	}

	/** Conta as tentativas aceitas antes do 429 e confirma o codigo devolvido. */
	private int acceptedBeforeThrottle(AuthService service) {
		int accepted = 0;
		while (true) {
			try {
				service.resendVerification(EMAIL);
				accepted++;
			}
			catch (ApiException throttle) {
				assertThat(throttle.status()).isEqualTo(429);
				assertThat(throttle.code()).isEqualTo("TOO_MANY_REQUESTS");
				return accepted;
			}
			if (accepted > VISIBLE_LIMIT + 5) {
				throw new AssertionError("reenvio nunca foi limitado");
			}
		}
	}

	@Test
	void enderecoDesconhecidoEConhecidoSaoLimitadosNoMesmoPonto() {
		when(profiles.findByEmail(anyString())).thenReturn(Optional.empty());
		int desconhecido = acceptedBeforeThrottle(service());

		UserProfile conta = UserProfile.create("user-1", "pessoa", EMAIL, "Pessoa", null, CLOCK.instant());
		when(profiles.findByEmail(anyString())).thenReturn(Optional.of(conta));
		int conhecido = acceptedBeforeThrottle(service());

		assertThat(desconhecido).isEqualTo(VISIBLE_LIMIT);
		assertThat(conhecido).isEqualTo(VISIBLE_LIMIT);
	}

	@Test
	void tetoPorContaNaoViraResposta() {
		UserProfile conta = UserProfile.create("user-2", "pessoa2", EMAIL, "Pessoa", null, CLOCK.instant());
		when(profiles.findByEmail(anyString())).thenReturn(Optional.of(conta));
		// Ja estourou o teto de 3 envios por hora daquela conta.
		when(tokens.countByUserIdSince(anyString(), any())).thenReturn(3);

		assertThatCode(() -> service().resendVerification(EMAIL)).doesNotThrowAnyException();
		verify(email, never()).sendVerificationEmail(anyString(), anyString(), anyString());
	}

	@Test
	void pendenteComTetoEstouradoTambemRespondeNeutro() {
		when(profiles.findByEmail(anyString())).thenReturn(Optional.empty());
		// O caminho de cadastro pendente nao pode ter resposta propria quando o teto estoura.
		assertThatCode(() -> service().resendVerification(EMAIL)).doesNotThrowAnyException();
		assertThatThrownBy(() -> {
			AuthService service = service();
			for (int i = 0; i <= VISIBLE_LIMIT; i++) {
				service.resendVerification(EMAIL);
			}
		}).isInstanceOfSatisfying(ApiException.class,
			error -> assertThat(error.code()).isEqualTo("TOO_MANY_REQUESTS"));
	}
}
