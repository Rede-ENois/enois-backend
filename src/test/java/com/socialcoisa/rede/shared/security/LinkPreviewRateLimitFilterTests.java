package com.socialcoisa.rede.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
 * O teto por pessoa da miniatura de link.
 *
 * <p>O ponto do filtro: o limite do processo nao segura um atacante com N pods e balde unico. Aqui
 * cada sessao tem o seu, e quem nao tem sessao divide o do IP. Tambem nao pode contar o resto da
 * API — so `/link-preview` com GET.</p>
 */
class LinkPreviewRateLimitFilterTests {

	private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

	private final ClientIpResolver ipResolver = mock(ClientIpResolver.class);

	private final RateLimitSessions sessions = mock(RateLimitSessions.class);

	private final LinkPreviewRateLimitFilter filter =
		new LinkPreviewRateLimitFilter(Clock.fixed(NOW, ZoneOffset.UTC), ipResolver, sessions, 3);

	private final CountingChain chain = new CountingChain();

	@Test
	void aSegundaPessoaNoMesmoIpLogadaTemOBaldeDela() throws Exception {
		when(ipResolver.resolve(any())).thenReturn("203.0.113.7");
		when(sessions.userId("sess-mel", "203.0.113.7")).thenReturn("mel");
		when(sessions.userId("sess-ana", "203.0.113.7")).thenReturn("ana");

		for (int i = 0; i < 3; i++) {
			assertThat(call("/link-preview", "sess-mel").getStatus()).isEqualTo(200);
		}
		// O balde do "mel" encheu; a Ana, no mesmo IP, ainda tem o dela.
		assertThat(call("/link-preview", "sess-mel").getStatus()).isEqualTo(429);
		assertThat(call("/link-preview", "sess-ana").getStatus()).isEqualTo(200);
	}

	@Test
	void anonimoDivideOBaldeDoIp() throws Exception {
		when(ipResolver.resolve(any())).thenReturn("203.0.113.7");

		for (int i = 0; i < 3; i++) {
			assertThat(call("/link-preview", null).getStatus()).isEqualTo(200);
		}

		MockHttpServletResponse cheia = call("/link-preview", null);
		assertThat(cheia.getStatus()).isEqualTo(429);
		assertThat(cheia.getContentAsString()).contains("LINK_PREVIEW_GUARD");
		assertThat(cheia.getHeader("Retry-After")).isEqualTo("60");
	}

	@Test
	void outrasRotasENaoGetNaoContam() throws Exception {
		when(ipResolver.resolve(any())).thenReturn("203.0.113.7");

		for (int i = 0; i < 10; i++) {
			assertThat(call("/feed/post", null).getStatus()).isEqualTo(200);
		}
		MockHttpServletRequest post = request("/link-preview", null);
		post.setMethod("POST");
		filter.doFilter(post, new MockHttpServletResponse(), chain);
		assertThat(chain.calls).isEqualTo(11);
		verify(sessions, never()).userId(anyString(), anyString());
	}

	private MockHttpServletResponse call(String path, String session) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request(path, session), response, chain);
		return response;
	}

	private MockHttpServletRequest request(String path, String session) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		request.setRemoteAddr("203.0.113.7");
		if (session != null) {
			request.addHeader("X-Session-Token", session);
		}
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
