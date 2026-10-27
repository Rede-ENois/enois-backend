package com.socialcoisa.rede.identity.adapters.out.oauth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.identity.application.port.out.TokenValidationResult;
import com.socialcoisa.rede.identity.application.port.out.TokenValidator;
import com.socialcoisa.rede.identity.domain.OAuthProvider;

/**
 * OAuth oficial do GitHub.
 *
 * <p>O GitHub não emite id_token: trocamos o `code` pelo access token no servidor e consultamos
 * `/user`. A identidade é o `id` numérico (imutável); o `login` é só apresentação e pode mudar.
 * Este fluxo é exclusivo para VINCULAR uma conta já existente — não cria conta no Énóis, porque o
 * GitHub não garante que o e-mail público seja verificado.</p>
 */
@Component
class GitHubTokenValidator implements TokenValidator {

	private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
	private static final String USER_URL = "https://api.github.com/user";

	private final OAuthProperties properties;
	private final HttpClient http;
	private final ObjectMapper json = new ObjectMapper();

	GitHubTokenValidator(OAuthProperties properties) {
		this.properties = properties;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
	}

	@Override
	public Optional<TokenValidationResult> validate(OAuthProvider provider, String credential, String expectedNonce) {
		if (provider != OAuthProvider.GITHUB || !StringUtils.hasText(credential)
				|| !properties.isGithubConfigured()) {
			return Optional.empty();
		}
		try {
			String accessToken = exchange(credential.trim());
			if (!StringUtils.hasText(accessToken)) {
				return Optional.empty();
			}
			return user(accessToken);
		}
		catch (Exception failure) {
			return Optional.empty();
		}
	}

	private String exchange(String code) throws Exception {
		var config = properties.getGithub();
		String body = "client_id=" + enc(config.getClientId())
			+ "&client_secret=" + enc(config.getClientSecret())
			+ "&code=" + enc(code)
			+ "&redirect_uri=" + enc(config.getRedirectUri());
		HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
			.timeout(Duration.ofSeconds(10))
			.header("Accept", "application/json")
			.header("Content-Type", "application/x-www-form-urlencoded")
			.header("User-Agent", "EnoisBot/1.0")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() >= 400) {
			return null;
		}
		JsonNode node = json.readTree(response.body());
		return node.path("access_token").asText("");
	}

	private Optional<TokenValidationResult> user(String accessToken) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(USER_URL))
			.timeout(Duration.ofSeconds(10))
			.header("Accept", "application/vnd.github+json")
			.header("X-GitHub-Api-Version", "2022-11-28")
			.header("User-Agent", "EnoisBot/1.0")
			.header("Authorization", "Bearer " + accessToken)
			.GET()
			.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() >= 400) {
			return Optional.empty();
		}
		JsonNode node = json.readTree(response.body());
		String id = node.path("id").asText("");
		String login = node.path("login").asText("");
		if (id.isBlank() || login.isBlank()) {
			return Optional.empty();
		}
		// E-mail do GitHub pode ser privado; não usamos para vincular por e-mail.
		String email = node.path("email").asText("");
		return Optional.of(new TokenValidationResult(id, email, login, false, accessToken, null));
	}

	private static String enc(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}
}
