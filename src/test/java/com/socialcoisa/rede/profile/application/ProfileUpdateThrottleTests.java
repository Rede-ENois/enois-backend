package com.socialcoisa.rede.profile.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.security.ActionThrottle;

/**
 * O teto de edição de perfil é POR CONTA, não por rede.
 *
 * <p>No QA todas as telas passam pelo servidor do front: se o teto contasse a rede, a primeira
 * conta a editar trinta vezes deixaria as outras sem editar — e a própria dona de uma conta ficava
 * barrada pelo uso de outra. O que sustenta isso: a chave do {@link ActionThrottle} é o usuário, e
 * nenhum limitador por IP cobre {@code PATCH /me/profile}.</p>
 */
class ProfileUpdateThrottleTests {

	private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);

	@Test
	void twoAccountsDoNotShareTheProfileBudget() {
		ActionThrottle throttle = new ActionThrottle();
		for (int i = 0; i < 30; i++) {
			throttle.exigePermitido("profile-update", "user_a", 30, java.time.Duration.ofHours(1));
		}

		assertThatCode(() -> throttle.exigePermitido("profile-update", "user_b", 30, java.time.Duration.ofHours(1)))
			.doesNotThrowAnyException();
	}

	@Test
	void theSameAccountStillHasItsCeiling() {
		ActionThrottle throttle = new ActionThrottle();
		for (int i = 0; i < 30; i++) {
			throttle.exigePermitido("profile-update", "user_a", 30, java.time.Duration.ofHours(1));
		}

		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> throttle.exigePermitido("profile-update", "user_a", 30,
				java.time.Duration.ofHours(1)))
			.isInstanceOfSatisfying(com.socialcoisa.rede.shared.web.ApiException.class,
				error -> org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("ACTION_THROTTLED"));
	}

	@Test
	void theProfileServiceKeysTheThrottleByAccount() {
		// O service recebe a conta do dono da sessão: a chave do teto é essa conta, nunca a rede de
		// onde o pedido chegou.
		var repository = mock(com.socialcoisa.rede.profile.application.port.out.UserProfileRepository.class);
		when(repository.findById(anyString())).thenReturn(java.util.Optional.empty());
		ActionThrottle throttle = mock(ActionThrottle.class);
		ProfileService service = new ProfileService(repository,
			mock(com.socialcoisa.rede.profile.application.ReputationService.class),
			mock(org.springframework.beans.factory.ObjectProvider.class),
			mock(com.socialcoisa.rede.age.application.port.out.ParentalLinkRepository.class),
			mock(com.socialcoisa.rede.profile.application.port.out.SocialPostCountReader.class),
			mock(com.socialcoisa.rede.shared.sanctions.SanctionGateReader.class),
			mock(com.socialcoisa.rede.profile.application.port.out.OfficialBadgeReader.class),
			mock(com.socialcoisa.rede.identity.application.port.out.IdentityVerificationRepository.class),
			throttle, mock(com.socialcoisa.rede.shared.application.CacheInvalidator.class),
			mock(com.socialcoisa.rede.media.application.MediaPublicHost.class), 30, clock);

		assertThatThrownBy(() -> service.update(new UpdateProfileCommand("user_a", null, null, null, null, null,
			null, null, null, null, null, java.util.List.of(), java.util.List.of(), null, null,
			java.util.List.of(), null, null, null, null, null, null, null, null, null, null, null)))
			.isInstanceOf(com.socialcoisa.rede.shared.web.ApiException.class);

		org.mockito.Mockito.verify(throttle).exigePermitido(org.mockito.ArgumentMatchers.eq("profile-update"),
			org.mockito.ArgumentMatchers.eq("user_a"), org.mockito.ArgumentMatchers.eq(30),
			org.mockito.ArgumentMatchers.any());
	}
}