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

/**
 * "Sign In with LinkedIn using OpenID Connect" — o produto de aprovacao automatica
 * do LinkedIn. Valida o id_token (RS256 via JWKS do LinkedIn, issuer, audience,
 * validade e nonce) e, quando chega `code`, troca no endpoint de token antes.
 *
 * O que o OIDC do LinkedIn entrega: sub, name, email, email_verified e picture.
 * EXPERIENCIA/EDUCACAO/SKILLS exigem aprovacao no programa de parceiros do LinkedIn —
 * quando o app for aprovado, os escopos entram aqui e o import ganha o resto.
 */
@Component
class LinkedInTokenValidator implements TokenValidator {

	// O endpoint antigo (api.linkedin.com/v2/openid/jwks) passou a exigir access token
	// (401 EMPTY_ACCESS_TOKEN); o JWKS publico e o do discovery document.
	private static final String LINKEDIN_JWKS_URL = "https://www.linkedin.com/oauth/openid/jwks";
	private static final String LINKEDIN_TOKEN_URL = "https://www.linkedin.com/oauth/v2/accessToken";
	private static final String LINKEDIN_ISSUER = "https://www.linkedin.com/oauth";

	private final OAuthProperties properties;
	private final HttpClient httpClient;

	private volatile JWKSet jwkSet;
	private volatile long jwkFetchTime;
	private static final long JWK_CACHE_TTL = 3600_000L;

	LinkedInTokenValidator(OAuthProperties properties) {
		this.properties = properties;
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();
	}

	@Override
	public Optional<TokenValidationResult> validate(OAuthProvider provider, String credential, String expectedNonce,
			String redirectUri) {
		if (provider != OAuthProvider.LINKEDIN || !StringUtils.hasText(credential)) {
			return Optional.empty();
		}

		if (!properties.isLinkedinConfigured()) {
			return Optional.empty();
		}

		if (isJwt(credential)) {
			return verifyIdToken(credential, expectedNonce);
		}

		return exchangeCodeForTokens(credential, expectedNonce, redirectUri);
	}

	private boolean isJwt(String value) {
		long dotCount = value.chars().filter(c -> c == '.').count();
		return dotCount == 2;
	}

	private Optional<TokenValidationResult> verifyIdToken(String idToken, String expectedNonce) {
		return verifyIdToken(idToken, expectedNonce, null, null);
	}

	private Optional<TokenValidationResult> verifyIdToken(String idToken, String expectedNonce,
			String accessToken, java.time.Instant accessTokenExpiresAt) {
		try {
			SignedJWT signedJWT = SignedJWT.parse(idToken);
			JWSVerifier verifier = new RSASSAVerifier(findRsaKey(signedJWT));

			if (!signedJWT.verify(verifier)) {
				return Optional.empty();
			}

			var claims = signedJWT.getJWTClaimsSet();

			String iss = claims.getIssuer();
			if (!LINKEDIN_ISSUER.equals(iss)) {
				return Optional.empty();
			}

			String aud = claims.getAudience().stream().findFirst().orElse("");
			String expectedClientId = properties.getLinkedin().getClientId();
			if (!expectedClientId.equals(aud)) {
				return Optional.empty();
			}

			var expiration = claims.getExpirationTime();
			if (expiration != null && expiration.before(new java.util.Date())) {
				return Optional.empty();
			}

			// O id_token do LinkedIn nao traz o claim nonce (so o state, validado e de uso unico
			// no backend, amarra o pedido). Conferimos quando o provedor devolve o claim.
			String nonce = claims.getStringClaim("nonce");
			if (StringUtils.hasText(expectedNonce) && StringUtils.hasText(nonce)
					&& !expectedNonce.equals(nonce)) {
				return Optional.empty();
			}

			String externalUserId = claims.getSubject();
			String email = claims.getStringClaim("email");
			String name = claims.getStringClaim("name");
			boolean emailVerified = isTruthy(claims.getClaim("email_verified"));

			return Optional.of(new TokenValidationResult(externalUserId, email, name, emailVerified,
				accessToken, accessTokenExpiresAt));
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	private boolean isTruthy(Object claim) {
		return Boolean.TRUE.equals(claim) || "true".equalsIgnoreCase(String.valueOf(claim));
	}

	private Optional<TokenValidationResult> exchangeCodeForTokens(String code, String expectedNonce,
			String redirectUri) {
		try {
			OAuthProperties.LinkedInConfig config = properties.getLinkedin();
			Map<String, String> params = new LinkedHashMap<>();
			params.put("code", code);
			params.put("client_id", config.getClientId());
			params.put("client_secret", config.getClientSecret());
			params.put("redirect_uri", redirectUri);
			params.put("grant_type", "authorization_code");

			String body = params.entrySet().stream()
				.map(e -> e.getKey() + "=" + java.net.URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8))
				.reduce((a, b) -> a + "&" + b)
				.orElse("");

			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(LINKEDIN_TOKEN_URL))
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

			// O mesmo token que autentica serve para publicar quando o vinculo pediu w_member_social;
			// a validade vem do expires_in (segundos) do proprio LinkedIn.
			String accessToken = (String) tokenResponse.get("access_token");
			Number expiresIn = (Number) tokenResponse.get("expires_in");
			java.time.Instant tokenExpiresAt = expiresIn == null ? null
				: java.time.Instant.now().plusSeconds(expiresIn.longValue());

			return verifyIdToken(idToken, expectedNonce, accessToken, tokenExpiresAt);
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

	private void refreshJwkSetIfStale() {
		long now = System.currentTimeMillis();
		if (jwkSet != null && now - jwkFetchTime < JWK_CACHE_TTL) {
			return;
		}
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(LINKEDIN_JWKS_URL))
				.timeout(Duration.ofSeconds(5))
				.GET()
				.build();
			HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
			if (response.statusCode() == 200) {
				jwkSet = JWKSet.parse(response.body());
				jwkFetchTime = now;
			}
		} catch (Exception e) {
			// mantém o JWKSet antigo; sem JWKS o validate devolve vazio (login falha, sem erro).
		}
	}
}
