package com.socialcoisa.rede.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Os dois tetos da leitura de assinatura (D7).
 *
 * <p>O caso que importa é a varredura: um token inventado por requisição sempre ganhava balde
 * novo, então nenhum teto por token limitava nada. O teto da origem é o que fecha isso — e à
 * custa de ser folgado, porque um serviço de calendário legítimo busca de poucos endereços em
 * nome de muita gente.
 */
class CalendarSubscriptionRateLimitFilterTests {

	private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

	private final ClientIpResolver ipResolver = mock(ClientIpResolver.class);

	private final CalendarSubscriptionRateLimitFilter filter =
		new CalendarSubscriptionRateLimitFilter(Clock.fixed(NOW, ZoneOffset.UTC), ipResolver, 3, 5);

	private final CountingChain chain = new CountingChain();

	@Test
	void sweepingInventedTokensStopsAtTheOriginLimitInsteadOfGettingAFreshBucketEachTime() throws Exception {
		when(ipResolver.resolve(any())).thenReturn("203.0.113.9");

		for (int i = 0; i < 5; i++) {
			assertThat(call("/calendar/subscribe/inventado-" + i + ".ics").getStatus()).isEqualTo(200);
		}

		MockHttpServletResponse refused = call("/calendar/subscribe/inventado-5.ics");
		assertThat(refused.getStatus()).isEqualTo(429);
		assertThat(refused.getContentAsString()).contains("CALENDAR_SUBSCRIPTION_GUARD");
		assertThat(refused.getHeader("Retry-After")).isEqualTo("60");
		assertThat(chain.calls).isEqualTo(5);
	}

	@Test
	void aSingleLinkHasItsOwnTighterCeiling() throws Exception {
		when(ipResolver.resolve(any())).thenReturn("203.0.113.10");

		for (int i = 0; i < 3; i++) {
			assertThat(call("/calendar/subscribe/meu-token.ics").getStatus()).isEqualTo(200);
		}
		assertThat(call("/calendar/subscribe/meu-token.ics").getStatus()).isEqualTo(429);
		// Outro link, mesma origem, ainda dentro do teto folgado da origem.
		assertThat(call("/calendar/subscribe/outro-token.ics").getStatus()).isEqualTo(200);
	}

	@Test
	void otherRoutesAndOtherMethodsAreNotCounted() throws Exception {
		when(ipResolver.resolve(any())).thenReturn("203.0.113.11");

		for (int i = 0; i < 10; i++) {
			assertThat(call("/calendar/entries").getStatus()).isEqualTo(200);
		}
		MockHttpServletRequest post = request("/calendar/subscribe/qualquer.ics");
		post.setMethod("POST");
		filter.doFilter(post, new MockHttpServletResponse(), chain);

		assertThat(chain.calls).isEqualTo(11);
		verify(ipResolver, never()).resolve(any());
	}

	private MockHttpServletResponse call(String path) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request(path), response, chain);
		return response;
	}

	private MockHttpServletRequest request(String path) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		request.setRemoteAddr("203.0.113.9");
		return request;
	}

	/** O que o filtro deixa passar, contado. */
	static class CountingChain implements jakarta.servlet.FilterChain {

		int calls;

		@Override
		public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
			calls++;
			((MockHttpServletResponse) response).setStatus(200);
		}
	}
}