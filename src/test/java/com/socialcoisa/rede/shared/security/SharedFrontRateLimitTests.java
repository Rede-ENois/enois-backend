package com.socialcoisa.rede.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Duas pessoas atrás do MESMO servidor de front não gastam o balde uma da outra.
 *
 * <p>É o efeito do E-03 no QA: todas as contas que usavam o front dividiam um teto de cadastro, de
 * recuperação de senha e de reenvio de e-mail. O front repassa o IP do navegador; o limite de
 * identidade passa a contar por ele, e o restante continua contando pela rede (ver
 * {@link ClientIpResolverProxiedClientTests}).</p>
 */
class SharedFrontRateLimitTests {

	private static final String FRONT_ORIGIN = "https://enois-qa.b7cloud.tech";

	private final ClientIpResolver resolver = ClientIpResolver.withRepassingFronts(FRONT_ORIGIN);
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);

	private MockHttpServletRequest signupFrom(String browserIp) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/signup");
		request.setRemoteAddr("10.0.0.5");
		request.addHeader("Origin", FRONT_ORIGIN);
		request.addHeader("X-Forwarded-For", "198.51.100.30, 172.69.1.2, " + browserIp);
		return request;
	}

	private RateLimitSessions sessions() {
		RateLimitSessions sessions = mock(RateLimitSessions.class);
		when(sessions.userId(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
			.thenReturn(null);
		return sessions;
	}

	private IdentityRateLimitFilter identityFilter(int signupLimit) {
		IpRateLimiter limiter = new IpRateLimiter(clock, signupLimit, 60, 20, 15, 5, 15, 5, 15, 20, 15, 5, 60,
			120, 15, 30, 60, 3, 60, 10, 15, 10, 5, 20, 5, 600, 1);
		return new IdentityRateLimitFilter(limiter, resolver);
	}

	private int statusOf(jakarta.servlet.Filter filter, MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response.getStatus();
	}

	@Test
	void twoBrowsersBehindTheSameFrontDoNotShareTheSignupQuota() throws Exception {
		IdentityRateLimitFilter filter = identityFilter(1);

		assertThat(statusOf(filter, signupFrom("203.0.113.20"))).isEqualTo(200);
		// O primeiro navegador esgotou a própria cota...
		assertThat(statusOf(filter, signupFrom("203.0.113.20"))).isEqualTo(429);
		// ...o segundo, atrás do mesmo front, continua livre.
		assertThat(statusOf(filter, signupFrom("203.0.113.21"))).isEqualTo(200);
	}

	@Test
	void theGlobalTetoStillCountsTheNetworkSoAWholeBlockCannotRotateIdentities() throws Exception {
		GlobalApiRateLimitFilter filter = new GlobalApiRateLimitFilter(clock, resolver, sessions(), 1, 50);

		MockHttpServletRequest first = new MockHttpServletRequest("POST", "/posts");
		first.setRemoteAddr("10.0.0.5");
		first.addHeader("Origin", FRONT_ORIGIN);
		first.addHeader("X-Forwarded-For", "198.51.100.30, 172.69.1.2, 203.0.113.20");
		MockHttpServletRequest second = new MockHttpServletRequest("POST", "/posts");
		second.setRemoteAddr("10.0.0.5");
		second.addHeader("Origin", FRONT_ORIGIN);
		second.addHeader("X-Forwarded-For", "198.51.100.30, 172.69.1.2, 203.0.113.21");

		assertThat(statusOf(filter, first)).isEqualTo(200);
		// Identidade diferente, mesma rede: o teto da rede vale para as duas.
		assertThat(statusOf(filter, second)).isEqualTo(200);
	}

	@Test
	void whenTheWireShowsTheEdgeTheGuardCountsTheEdge() throws Exception {
		EnumerationRiskEngine engine = mock(EnumerationRiskEngine.class);
		when(engine.observe(org.mockito.ArgumentMatchers.any())).thenReturn(new EnumerationRiskEngine.Assessment(
			EnumerationRiskEngine.Level.NONE, 0, List.of(), 0, 0, false));
		EnumerationGuardFilter filter = new EnumerationGuardFilter(engine, resolver, sessions(), clock, "shadow", 5, 60);
		// Quando o proxy da frente entrega só o salto dele, o item do fim é a borda: é a ela que a
		// varredura anônima se refere, nunca à identidade que o front repassou.
		for (String browser : List.of("203.0.113.20", "203.0.113.21")) {
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users/u" + browser.replace('.', 'x'));
			request.setRequestURI("/users/u" + browser.replace('.', 'x'));
			request.setRemoteAddr("10.0.0.5");
			request.addHeader("Origin", FRONT_ORIGIN);
			request.addHeader("X-Forwarded-For", "198.51.100.30, 172.69.1.2");
			statusOf(filter, request);
		}
		org.mockito.ArgumentCaptor<EnumerationRiskEngine.Observation> observed =
			org.mockito.ArgumentCaptor.forClass(EnumerationRiskEngine.Observation.class);
		verify(engine, org.mockito.Mockito.times(2)).observe(observed.capture());
		assertThat(observed.getAllValues()).allSatisfy(observation -> {
			assertThat(observation.key()).isEqualTo("ip:172.69.1.2");
			assertThat(observation.authenticated()).isFalse();
		});
	}
}