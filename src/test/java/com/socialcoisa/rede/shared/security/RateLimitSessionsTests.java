package com.socialcoisa.rede.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;

@Tag("critico")
class RateLimitSessionsTests {

	private final Clock clock = Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC);
	private final AuthSessionRepository repository = mock(AuthSessionRepository.class);

	@SuppressWarnings("unchecked")
	private RateLimitSessions sessions() {
		ObjectProvider<AuthSessionRepository> provider = mock(ObjectProvider.class);
		when(provider.getObject()).thenReturn(repository);
		return new RateLimitSessions(provider, clock, new ClientIpResolver(), 50);
	}

	@Test
	void aLiveSessionIsLookedUpOnceAndThenServedFromTheCache() {
		when(repository.findActiveUserId("live", clock.instant())).thenReturn(Optional.of("user_a"));
		RateLimitSessions sessions = sessions();

		assertThat(sessions.userId("live", "203.0.113.1")).isEqualTo("user_a");
		assertThat(sessions.userId("live", "203.0.113.1")).isEqualTo("user_a");
		verify(repository, times(1)).findActiveUserId(anyString(), any());
	}

	@Test
	void inventedTokensStopReachingTheDatabaseOnceTheIpLookupBudgetIsSpent() {
		when(repository.findActiveUserId(anyString(), any())).thenReturn(Optional.empty());
		RateLimitSessions sessions = sessions();

		for (int i = 0; i < 200; i++) {
			assertThat(sessions.userId("invented-" + i, "203.0.113.2")).isNull();
		}
		verify(repository, times(60)).findActiveUserId(anyString(), any());

		// Another address has its own budget.
		sessions.userId("invented-x", "203.0.113.3");
		verify(repository, times(61)).findActiveUserId(anyString(), any());
	}

	@Test
	void aTrustedProxyAddressGetsTheSharedMultiplier() {
		when(repository.findActiveUserId(anyString(), any())).thenReturn(Optional.empty());
		RateLimitSessions sessions = sessions();

		// Private ranges are trusted proxies by default: one address in front of many people.
		for (int i = 0; i < 61; i++) {
			sessions.userId("behind-proxy-" + i, "10.0.0.1");
		}
		verify(repository, times(61)).findActiveUserId(anyString(), any());
	}
}
