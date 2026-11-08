package com.socialcoisa.rede.identity.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.identity.application.port.out.TokenValidationResult;
import com.socialcoisa.rede.identity.application.port.out.TokenValidator;
import com.socialcoisa.rede.identity.domain.OAuthProvider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A login started on the mirror domain stays on it: the provider gets the mirror's redirect_uri, the
 * code exchange repeats it, and the browser comes back to the mirror's app, whose API host holds the
 * session cookies. A login started on the primary domain is unchanged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"rede.site.domains=enois.ia.br,isweee.com",
		// Same allowlist as production (application-prd.properties): both sites call the API.
		"rede.cors.allowed-origins=https://enois.ia.br,https://isweee.com",
		"rede.oauth.google.client-id=test-client-id",
		"rede.oauth.google.client-secret=test-client-secret",
		"rede.oauth.google.redirect-uri=https://enois.ia.br/auth/callback?provider=GOOGLE",
		"rede.oauth.frontend-redirect-url=https://enois.ia.br/auth/callback" })
class OAuthMirrorDomainTests {

	private static final String MIRROR_CALLBACK = "https://isweee.com/auth/callback?provider=GOOGLE";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean(name = "googleTokenValidator")
	private TokenValidator googleValidator;

	@Test
	void aLoginStartedOnTheMirrorUsesAndReturnsToTheMirror() throws Exception {
		JsonNode started = start("https://isweee.com");
		assertThat(redirectUriOf(started)).isEqualTo(MIRROR_CALLBACK);

		when(googleValidator.validate(eq(OAuthProvider.GOOGLE), anyString(), anyString(), eq(MIRROR_CALLBACK)))
			.thenReturn(Optional.of(new TokenValidationResult("google-sub-espelho", "espelho@example.com",
				"Pessoa Espelho", true)));

		mockMvc.perform(get("/auth/oauth/google/callback").param("code", "codigo-valido")
				.param("state", started.get("state").asText()))
			.andExpect(status().isFound())
			.andExpect(redirectedUrl("https://isweee.com/auth/callback?status=ok"));
		verify(googleValidator).validate(eq(OAuthProvider.GOOGLE), eq("codigo-valido"), anyString(),
			eq(MIRROR_CALLBACK));
	}

	@Test
	void aFailureOnTheMirrorAlsoReturnsToTheMirror() throws Exception {
		JsonNode started = start("https://isweee.com");

		mockMvc.perform(get("/auth/oauth/google/callback").param("error", "access_denied")
				.param("state", started.get("state").asText()))
			.andExpect(status().isFound())
			.andExpect(redirectedUrl("https://isweee.com/auth/callback?error=ACCESS_DENIED&provider=GOOGLE"));
	}

	@Test
	void theClientOriginHeaderIdentifiesTheMirrorWhenTheBrowserSendsNoOrigin() throws Exception {
		JsonNode started = objectMapper.readTree(mockMvc.perform(get("/auth/oauth/google/start")
				.header("X-Client-Origin", "https://isweee.com"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

		assertThat(redirectUriOf(started)).isEqualTo(MIRROR_CALLBACK);
	}

	@Test
	void theCallbackStepDoesNotLetTheOriginPickTheSite() throws Exception {
		// The site is fixed when the flow starts; a callback carrying another Origin does not move it.
		JsonNode started = start("https://enois.ia.br");

		mockMvc.perform(get("/auth/oauth/google/callback").header("Origin", "https://isweee.com")
				.param("error", "access_denied").param("state", started.get("state").asText()))
			.andExpect(status().isFound())
			.andExpect(redirectedUrl("https://enois.ia.br/auth/callback?error=ACCESS_DENIED&provider=GOOGLE"));
	}

	@Test
	void thePrimaryOriginOrNoOriginStaysOnThePrimaryDomain() throws Exception {
		assertThat(redirectUriOf(start("https://enois.ia.br")))
			.isEqualTo("https://enois.ia.br/auth/callback?provider=GOOGLE");

		JsonNode noOrigin = objectMapper.readTree(mockMvc.perform(get("/auth/oauth/google/start"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(redirectUriOf(noOrigin)).isEqualTo("https://enois.ia.br/auth/callback?provider=GOOGLE");
	}

	private JsonNode start(String origin) throws Exception {
		return objectMapper.readTree(mockMvc.perform(get("/auth/oauth/google/start").header("Origin", origin))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private static String redirectUriOf(JsonNode started) {
		String url = started.get("authorizationUrl").asText();
		String encoded = url.replaceAll(".*[?&]redirect_uri=([^&]*).*", "$1");
		return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
	}
}
