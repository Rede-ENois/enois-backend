package com.socialcoisa.rede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

/**
 * The central route lock covers `/moderation/**` like `/admin/**`: a moderation route is closed to
 * plain members before any controller runs, so a new handler there cannot forget the check.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModerationRouteInterceptorTests {

	private static final List<String> MODERATOR_ROUTES = List.of(
		"/moderation/reports", "/moderation/quarantine", "/moderation/community-projects",
		"/moderation/posts/suspicious", "/moderation/sanction-proposals", "/moderation/games/x/approve");

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "modroute_admin@test.com");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private AdminRouteInterceptor interceptor;

	private boolean preHandle(String method, String path, String token) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		if (token != null) {
			request.addHeader("X-Session-Token", token);
		}
		return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
	}

	@Test
	void plainMemberIsRefusedOnEveryModerationRouteBeforeTheController() throws Exception {
		String token = new TestAuthHelper(mockMvc, objectMapper).authenticate("modroute_plain").token();

		for (String path : MODERATOR_ROUTES) {
			assertThatThrownBy(() -> preHandle("GET", path, token))
				.as(path)
				.isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(403));
		}
	}

	@Test
	void moderationRouteWithoutSessionIsUnauthenticated() {
		assertThatThrownBy(() -> preHandle("GET", "/moderation/reports", null))
			.isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(401));
		assertThatThrownBy(() -> preHandle("GET", "/moderation/me", null))
			.isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(401));
	}

	@Test
	void plainMemberStillReachesTheirEligibilityAndTheApplication() throws Exception {
		String token = new TestAuthHelper(mockMvc, objectMapper).authenticate("modroute_member").token();

		assertThat(preHandle("GET", "/moderation/me", token)).isTrue();
		assertThat(preHandle("POST", "/moderation/applications", token)).isTrue();
		assertThatThrownBy(() -> preHandle("GET", "/moderation/applications/extra", token))
			.isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(403));
		mockMvc.perform(get("/moderation/me").header("X-Session-Token", token))
			.andExpect(status().isOk());
	}

	@Test
	void adminPassesTheModerationLock() throws Exception {
		String token = new TestAuthHelper(mockMvc, objectMapper).authenticate("modroute_admin").token();

		for (String path : MODERATOR_ROUTES) {
			assertThat(preHandle("GET", path, token)).as(path).isTrue();
		}
		mockMvc.perform(get("/moderation/reports").header("X-Session-Token", token))
			.andExpect(status().isOk());
	}

	@Test
	void lookalikePrefixesAreNotModerationRoutes() throws Exception {
		for (String path : List.of("/moderations/reports", "/moderationx", "/users/moderation")) {
			assertThat(preHandle("GET", path, null)).as(path).isTrue();
		}
	}
}
