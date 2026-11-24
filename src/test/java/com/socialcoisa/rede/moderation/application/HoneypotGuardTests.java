package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.moderation.application.port.out.ModeratorApplicationRepository;
import com.socialcoisa.rede.moderation.application.port.out.SanctionRepository;
import com.socialcoisa.rede.moderation.domain.UserSanction;
import com.socialcoisa.rede.shared.web.AdminGuard;

@Tag("critico")
class HoneypotGuardTests {

	private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC);

	@Test
	void emptyFieldDoesNothing() {
		SanctionService sanctions = mock(SanctionService.class);
		var guard = guard(sanctions, mock(SanctionRepository.class), mock(AdminGuard.class),
			mock(ModeratorApplicationRepository.class));

		assertThat(guard.triggered("user_1", "  ", "post")).isFalse();
		verify(sanctions, never()).banAuthor(any(), any(), any(), any());
	}

	@Test
	void filledTrapBansAndRejects() {
		SanctionService sanctions = mock(SanctionService.class);
		SanctionRepository sanctionRepository = mock(SanctionRepository.class);
		AdminGuard admins = mock(AdminGuard.class);
		ModeratorApplicationRepository moderators = mock(ModeratorApplicationRepository.class);
		when(moderators.findApprovedByUser("user_1")).thenReturn(Optional.empty());
		when(sanctionRepository.findActiveByUser("user_1")).thenReturn(Optional.empty());
		var guard = guard(sanctions, sanctionRepository, admins, moderators);

		assertThat(guard.triggered("user_1", "https://bot.example", "post")).isTrue();
		verify(sanctions).banAuthor(any(UserSanction.class), org.mockito.ArgumentMatchers.eq("user_1"),
			org.mockito.ArgumentMatchers.eq("honeypot"), any());
	}

	@Test
	void staffIsRejectedButNotBanned() {
		SanctionService sanctions = mock(SanctionService.class);
		AdminGuard admins = mock(AdminGuard.class);
		when(admins.isAdmin(any())).thenReturn(true);
		var guard = guard(sanctions, mock(SanctionRepository.class), admins,
			mock(ModeratorApplicationRepository.class));

		assertThat(guard.triggered("user_admin", "x", "post")).isTrue();
		verify(sanctions, never()).banAuthor(any(), any(), any(), any());
	}

	private HoneypotGuard guard(SanctionService sanctions, SanctionRepository sanctionRepository,
			AdminGuard admins, ModeratorApplicationRepository moderators) {
		return new HoneypotGuard(sanctions, sanctionRepository, mock(AuthSessionRepository.class), admins,
			moderators, clock, true);
	}
}
