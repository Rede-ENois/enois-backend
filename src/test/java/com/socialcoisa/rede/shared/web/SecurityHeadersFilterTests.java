package com.socialcoisa.rede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;

class SecurityHeadersFilterTests {

	private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		new SecurityHeadersFilter().doFilter(request, response, new MockFilterChain());
		return response;
	}

	@Test
	void setsIsolationHeadersOnEveryResponse() throws Exception {
		MockHttpServletResponse response = run(new MockHttpServletRequest("GET", "/posts"));
		assertThat(response.getHeader("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");
		assertThat(response.getHeader("Cross-Origin-Resource-Policy")).isEqualTo("same-site");
		assertThat(response.getHeader("Cache-Control")).isNull();
	}

	@Test
	void authenticatedByHeaderIsNotCached() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/me");
		request.addHeader("X-Session-Token", "token");
		assertThat(run(request).getHeader("Cache-Control")).isEqualTo("no-store");
	}

	@Test
	void authenticatedByCookieIsNotCached() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/me");
		request.setCookies(new Cookie("enois_session", "token"));
		assertThat(run(request).getHeader("Cache-Control")).isEqualTo("no-store");
	}

	@Test
	void semServidorDeReuniaoAMidiaFicaSoNaPropriaPagina() throws Exception {
		String policy = run(new MockHttpServletRequest("GET", "/version")).getHeader("Permissions-Policy");
		assertThat(policy).isEqualTo(
			"geolocation=(), microphone=(self), camera=(self), display-capture=(self)");
	}

	@Test
	void aMidiaVaiAteOServidorDeReuniaoENenhumOutro() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		new SecurityHeadersFilter("https://meet.example.test/").doFilter(new MockHttpServletRequest("GET", "/version"),
			response, new MockFilterChain());
		String policy = response.getHeader("Permissions-Policy");
		assertThat(policy).contains("microphone=(self \"https://meet.example.test\")");
		assertThat(policy).contains("camera=(self \"https://meet.example.test\")");
		assertThat(policy).contains("display-capture=(self \"https://meet.example.test\")");
		assertThat(policy).doesNotContain("microphone=()").doesNotContain("camera=()");
	}

	@Test
	void aOrigemDoServidorDeReuniaoVaiEntreAspas() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		new SecurityHeadersFilter("https://meet.example.test").doFilter(new MockHttpServletRequest("GET", "/version"),
			response, new MockFilterChain());
		// Sem as aspas o navegador descarta o item e a sala abre sem mídia.
		assertThat(response.getHeader("Permissions-Policy")).doesNotContain("(self https://");
	}

	@Test
	void servidorDeReuniaoComPortaMantemAPorta() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		new SecurityHeadersFilter("http://127.0.0.1:8443").doFilter(new MockHttpServletRequest("GET", "/version"),
			response, new MockFilterChain());
		assertThat(response.getHeader("Permissions-Policy")).contains("camera=(self \"http://127.0.0.1:8443\")");
	}

	@Test
	void urlDeReuniaoMalformadaNaoAbreAMidiaParaNinguem() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		new SecurityHeadersFilter("nao e uma url").doFilter(new MockHttpServletRequest("GET", "/version"), response,
			new MockFilterChain());
		assertThat(response.getHeader("Permissions-Policy")).contains("microphone=(self)")
			.contains("camera=(self)");
	}
}
