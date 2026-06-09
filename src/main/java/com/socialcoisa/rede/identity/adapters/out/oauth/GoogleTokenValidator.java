package com.socialcoisa.rede.identity.adapters.out.oauth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.socialcoisa.rede.identity.application.port.out.TokenValidationResult;
import com.socialcoisa.rede.identity.application.port.out.TokenValidator;
import com.socialcoisa.rede.identity.domain.OAuthProvider;

@Component
class GoogleTokenValidator implements TokenValidator {

	private static final String GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
	private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";

	private final OAuthProperties properties;
	private final HttpClient httpClient;

	private volatile JWKSet jwkSet;
	private volatile long jwkFetchTime;
	private static final long JWK_CACHE_TTL = 3600_000L;

	GoogleTokenValidator(OAuthProperties properties) {
		this.properties = properties;
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();
	}

	@Override
	public Optional<TokenValidationResult> validate(OAuthProvider provider, String authorizationCode) {
		if (provider != OAuthProvider.GOOGLE || !StringUtils.hasText(authorizationCode)) {
			return Optional.empty();
		}

		if (!properties.isGoogleConfigured()) {
			return Optional.empty();
		}

		if (isJwt(authorizationCode)) {
			return verifyIdToken(authorizationCode);
		}

		return exchangeCodeForTokens(authorizationCode);
	}

	private boolean isJwt(String value) {
		long dotCount = value.chars().filter(c -> c == '.').count();
		return dotCount == 2;
	}

	private Optional<TokenValidationResult> verifyIdToken(String idToken) {
		try {
			SignedJWT signedJWT = SignedJWT.parse(idToken);
			JWSVerifier verifier = new RSASSAVerifier(findRsaKey(signedJWT));

			if (!signedJWT.verify(verifier)) {
				return Optional.empty();
			}

			var claims = signedJWT.getJWTClaimsSet();

			String iss = claims.getIssuer();
			if (!"https://accounts.google.com".equals(iss) && !"accounts.google.com".equals(iss)) {
				return Optional.empty();
			}

			String aud = claims.getAudience().stream().findFirst().orElse("");
			String expectedClientId = properties.getGoogle().getClientId();
			if (!expectedClientId.equals(aud)) {
				return Optional.empty();
			}

			var expiration = claims.getExpirationTime();
			if (expiration != null && expiration.before(new java.util.Date())) {
				return Optional.empty();
			}

			String externalUserId = claims.getSubject();
			String email = claims.getStringClaim("email");
			String name = claims.getStringClaim("name");

			return Optional.of(new TokenValidationResult(externalUserId, email, name));
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	private Optional<TokenValidationResult> exchangeCodeForTokens(String code) {
		try {
			OAuthProperties.GoogleConfig config = properties.getGoogle();
			Map<String, String> params = new LinkedHashMap<>();
			params.put("code", code);
			params.put("client_id", config.getClientId());
			params.put("client_secret", config.getClientSecret());
			params.put("redirect_uri", config.getRedirectUri());
			params.put("grant_type", "authorization_code");

			String body = params.entrySet().stream()
				.map(e -> e.getKey() + "=" + java.net.URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8))
				.reduce((a, b) -> a + "&" + b)
				.orElse("");

			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(GOOGLE_TOKEN_URL))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();

			HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				return Optional.empty();
			}

			@SuppressWarnings("unchecked")
			Map<String, Object> tokenResponse = new ObjectMapper().readValue(response.body(), Map.class);
			String idToken = (String) tokenResponse.get("id_token");

			if (!StringUtils.hasText(idToken)) {
				return Optional.empty();
			}

			return verifyIdToken(idToken);
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	private RSAKey findRsaKey(SignedJWT signedJWT) throws Exception {
		refreshJwkSetIfStale();
		String kid = signedJWT.getHeader().getKeyID();
		if (kid == null) {
			JWK single = jwkSet.getKeys().stream().findFirst()
				.orElseThrow(() -> new IllegalStateException("No keys in JWKS"));
			return single.toRSAKey();
		}
		JWK jwk = jwkSet.getKeyByKeyId(kid);
		if (jwk == null) {
			throw new IllegalStateException("No JWK found for kid: " + kid);
		}
		return jwk.toRSAKey();
	}

	private void refreshJwkSetIfStale() throws Exception {
		if (jwkSet == null || System.currentTimeMillis() - jwkFetchTime > JWK_CACHE_TTL) {
			java.net.URL url = URI.create(GOOGLE_JWKS_URL).toURL();
			jwkSet = JWKSet.load(url);
			jwkFetchTime = System.currentTimeMillis();
		}
	}
}
