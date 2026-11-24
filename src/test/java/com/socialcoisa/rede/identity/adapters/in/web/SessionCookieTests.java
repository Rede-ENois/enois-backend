package com.socialcoisa.rede.identity.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import tools.jackson.databind.ObjectMapper;

/** Browser sessions travel in HttpOnly cookies; cookie-authenticated writes need the CSRF header. */
// The test profile is dev, which turns Secure off for plain-http local runs; production keeps the default.
@Tag("critico")
@SpringBootTest(properties = "rede.auth.cookie-secure=true")
@AutoConfigureMockMvc
class SessionCookieTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void loginSetsHardenedCookiesAndKeepsTokensOutOfTheBody() throws Exception {
		new TestAuthHelper(mockMvc, objectMapper).authenticate("cookie_login");

		MvcResult login = login("cookie_login@test.com");

		assertThat(login.getResponse().getContentAsString()).doesNotContain("\"token\"")
			.doesNotContain("refreshToken");
		List<String> setCookies = login.getResponse().getHeaders("Set-Cookie");
		assertThat(setCookies).anySatisfy(value -> assertThat(value).startsWith("enois_session=")
			.contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/", "Max-Age="));
		assertThat(setCookies).anySatisfy(value -> assertThat(value).startsWith("enois_refresh=")
			.contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/", "Max-Age="));
	}

	@Test
	void cookieAuthenticatesReadsAndWritesNeedTheCsrfHeader() throws Exception {
		new TestAuthHelper(mockMvc, objectMapper).authenticate("cookie_csrf");
		Cookie session = new Cookie("enois_session", TestAuthHelper.sessionToken(login("cookie_csrf@test.com")));

		mockMvc.perform(get("/auth/me").cookie(session)).andExpect(status().isOk());

		mockMvc.perform(post("/auth/logout").cookie(session))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CSRF_REQUIRED"));
		// The browser must be able to read the refusal from the app's own origin, and only from it.
		mockMvc.perform(post("/auth/logout").cookie(session).header("Origin", "https://enois.ia.br"))
			.andExpect(status().isForbidden())
			.andExpect(header().string("Access-Control-Allow-Origin", "https://enois.ia.br"))
			.andExpect(header().string("Access-Control-Allow-Credentials", "true"));
		mockMvc.perform(post("/auth/logout").cookie(session).header("Origin", "https://attacker.example"))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
			.andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));

		MvcResult logout = mockMvc.perform(post("/auth/logout").cookie(session)
				.header("X-CSRF-Protection", "1").header("Idempotency-Key", "cookie-logout"))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(logout.getResponse().getHeaders("Set-Cookie"))
			.anySatisfy(value -> assertThat(value).startsWith("enois_session=;").contains("Max-Age=0"))
			.anySatisfy(value -> assertThat(value).startsWith("enois_refresh=;").contains("Max-Age=0"));

		mockMvc.perform(get("/auth/me").cookie(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void refreshReadsTheRefreshCookieWhenTheBodyIsEmpty() throws Exception {
		new TestAuthHelper(mockMvc, objectMapper).authenticate("cookie_refresh");
		MvcResult login = login("cookie_refresh@test.com");
		Cookie refresh = new Cookie("enois_refresh", TestAuthHelper.refreshToken(login));

		mockMvc.perform(post("/auth/refresh").cookie(refresh)).andExpect(status().isForbidden());

		MvcResult refreshed = mockMvc.perform(post("/auth/refresh").cookie(refresh).header("X-CSRF-Protection", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.token").doesNotExist())
			.andReturn();
		String newSession = TestAuthHelper.sessionToken(refreshed);
		mockMvc.perform(get("/auth/me").cookie(new Cookie("enois_session", newSession))).andExpect(status().isOk());
	}

	@Test
	void logoutWithOnlyTheRefreshCookieRevokesTheSession() throws Exception {
		new TestAuthHelper(mockMvc, objectMapper).authenticate("cookie_refresh_logout");
		Cookie refresh = new Cookie("enois_refresh", TestAuthHelper.refreshToken(login("cookie_refresh_logout@test.com")));

		// The access cookie has expired: only the refresh cookie is sent.
		mockMvc.perform(post("/auth/logout").cookie(refresh).header("X-CSRF-Protection", "1")
				.header("Idempotency-Key", "cookie-refresh-logout"))
			.andExpect(status().isOk());

		mockMvc.perform(post("/auth/refresh").cookie(refresh).header("X-CSRF-Protection", "1"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void anExplicitSessionHeaderNeedsNoCsrfHeader() throws Exception {
		var session = new TestAuthHelper(mockMvc, objectMapper).authenticate("cookie_header_client");

		mockMvc.perform(post("/auth/logout").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "header-logout"))
			.andExpect(status().isOk());
	}

	private MvcResult login(String email) throws Exception {
		return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("email", email, "password", "Test1234!"))))
			.andExpect(status().isOk())
			.andReturn();
	}
}
