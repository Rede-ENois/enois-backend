package com.socialcoisa.rede.identity.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

/**
 * O refresh token tem vida propria (30 dias) e nao pode morrer junto com o access token (60 min).
 * Antes, a sessao so validava a expiracao do access token, entao renovar depois de um tempo ausente
 * falhava e o usuario era jogado para o login — o "logout involuntario" relatado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("critico")
class SessionRefreshTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void refreshWorksAfterAccessTokenExpires() throws Exception {
		var session = auth.authenticate("user_refresh_alive");

		// O access token venceu, mas o refresh ainda esta na janela de 30 dias.
		TestDatabase.expireAccessToken(session.token(), Instant.now().minusSeconds(60));

		// O access token vencido nao serve mais para autenticar.
		mockMvc.perform(get("/auth/me").header("X-Session-Token", session.token()))
			.andExpect(status().isUnauthorized());

		// Mas o refresh continua valido e devolve uma sessao nova que funciona.
		MvcResult refreshed = mockMvc.perform(post("/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("refreshToken", session.refreshToken()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.token").doesNotExist())
			.andExpect(jsonPath("$.refreshToken").doesNotExist())
			.andReturn();

		String newToken = TestAuthHelper.sessionToken(refreshed);
		assertThat(newToken).isNotEqualTo(session.token());

		mockMvc.perform(get("/auth/me").header("X-Session-Token", newToken))
			.andExpect(status().isOk());
	}

	@Test
	void refreshRejectedWhenRefreshTokenExpired() throws Exception {
		var session = auth.authenticate("user_refresh_dead");

		TestDatabase.expireRefreshToken(session.refreshToken(), Instant.now().minusSeconds(60));

		mockMvc.perform(post("/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("refreshToken", session.refreshToken()))))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	@Test
	void refreshRotatesTokenAndRejectsTheOldOne() throws Exception {
		var session = auth.authenticate("user_refresh_rotate");

		MvcResult first = mockMvc.perform(post("/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("refreshToken", session.refreshToken()))))
			.andExpect(status().isOk())
			.andReturn();
		String rotatedRefresh = TestAuthHelper.refreshToken(first);

		// O refresh token antigo foi revogado pela rotacao e nao pode ser reutilizado.
		mockMvc.perform(post("/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("refreshToken", session.refreshToken()))))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

		// O novo continua valido.
		mockMvc.perform(post("/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("refreshToken", rotatedRefresh))))
			.andExpect(status().isOk());
	}
}
