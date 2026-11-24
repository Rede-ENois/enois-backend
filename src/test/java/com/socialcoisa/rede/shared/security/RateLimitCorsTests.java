package com.socialcoisa.rede.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.socialcoisa.rede.shared.web.TrustedOriginCors;

/**
 * O 429 dos filtros precisa carregar CORS: sem ele o navegador lê a recusa como falha de rede e a
 * pessoa vê "não foi possível..." sem status nenhum — foi assim que a reação pareceu quebrada.
 */
@Tag("critico")
class RateLimitCorsTests {

	private final ClientIpResolver resolver = new ClientIpResolver();

	@Test
	void globalLimitAnswers429WithCorsHeaders() throws Exception {
		Clock clock = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);
		GlobalApiRateLimitFilter filter = new GlobalApiRateLimitFilter(clock, resolver,
			org.mockito.Mockito.mock(RateLimitSessions.class), 1, 50);

		MockHttpServletRequest first = post("/feed/posts/x/reactions");
		filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());

		MockHttpServletRequest second = post("/feed/posts/x/reactions");
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(second, response, new MockFilterChain());

		assertThat(response.getStatus()).isEqualTo(429);
		assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://enois.ia.br");
		assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
		assertThat(response.getHeader("Vary")).contains("Origin");
		assertThat(response.getHeader("Access-Control-Allow-Headers")).isNotBlank();
		assertThat(response.getHeader("Retry-After")).isNotBlank();
		assertThat(response.getContentAsString()).contains("RATE_LIMITED");
	}

	@Test
	void untrustedOriginGetsNoCredentialedCors() throws Exception {
		Clock clock = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);
		GlobalApiRateLimitFilter filter = new GlobalApiRateLimitFilter(clock, resolver,
			org.mockito.Mockito.mock(RateLimitSessions.class), 1, 50);

		filter.doFilter(untrusted("/feed/posts/y/reactions"), new MockHttpServletResponse(), new MockFilterChain());
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(untrusted("/feed/posts/y/reactions"), response, new MockFilterChain());

		assertThat(response.getStatus()).isEqualTo(429);
		assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
		assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
	}

	@Test
	void preflightIsNeverCountedByTheGlobalLimit() throws Exception {
		Clock clock = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);
		GlobalApiRateLimitFilter filter = new GlobalApiRateLimitFilter(clock, resolver,
			org.mockito.Mockito.mock(RateLimitSessions.class), 1, 50);

		MockHttpServletRequest options = new MockHttpServletRequest("OPTIONS", "/feed/posts/x/reactions");
		options.addHeader("Origin", "https://enois.ia.br");
		options.addHeader("X-Forwarded-For", "203.0.113.9");
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(options, response, new MockFilterChain());

		// Filtro não atua em OPTIONS: o CORS do MVC responde o preflight.
		assertThat(response.getStatus()).isNotEqualTo(429);
	}

	private static MockHttpServletRequest post(String path) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
		request.addHeader("Origin", "https://enois.ia.br");
		request.addHeader("Access-Control-Request-Headers", "content-type,x-session-token,idempotency-key");
		request.addHeader("X-Forwarded-For", "203.0.113.9");
		// What the origin gate (which runs first) does for the app's own origin.
		TrustedOriginCors.markTrusted(request, "https://enois.ia.br");
		return request;
	}

	private static MockHttpServletRequest untrusted(String path) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
		request.addHeader("Origin", "https://attacker.example");
		request.addHeader("X-Forwarded-For", "203.0.113.10");
		return request;
	}
}
