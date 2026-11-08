package com.socialcoisa.rede.identity.adapters.out.oauth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.socialcoisa.rede.identity.application.port.out.TokenValidationResult;
import com.socialcoisa.rede.identity.application.port.out.TokenValidator;
import com.socialcoisa.rede.identity.domain.OAuthProvider;

/**
 * Valida o login da Apple.
 *
 * A Apple entrega um `code` (fluxo web) ou um id_token (fluxo nativo). No fluxo web e preciso trocar
 * o code por tokens antes de validar o id_token, e essa troca exige um `client_secret` que nao existe
 * pronto: ele e um JWT ES256 assinado com a chave .p8 do time (teamId + keyId), gerado aqui.
 */
@Component
class AppleTokenValidator implements TokenValidator {

	private static final String APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys";
	private static final String APPLE_TOKEN_URL = "https://appleid.apple.com/auth/token";
	private static final String APPLE_ISS = "https://appleid.apple.com";
	/** A Apple limita o client_secret a 6 meses; usamos 5 para renovar sempre antes de vencer. */
	private static final long CLIENT_SECRET_TTL_MS = Duration.ofDays(150).toMillis();

	private final OAuthProperties properties;
	private final HttpClient httpClient;

	private volatile JWKSet jwkSet;
	private volatile long jwkFetchTime;
	private static final long JWK_CACHE_TTL = 3600_000L;

	AppleTokenValidator(OAuthProperties properties) {
		this.properties = properties;
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();
	}

	@Override
	public Optional<TokenValidationResult> validate(OAuthProvider provider, String credential, String expectedNonce,
			String redirectUri) {
		if (provider != OAuthProvider.APPLE || !StringUtils.hasText(credential)) {
			return Optional.empty();
		}

		if (!properties.isAppleConfigured()) {
			return Optional.empty();
		}

		if (isJwt(credential)) {
			return verifyIdentityToken(credential, expectedNonce);
		}

		return exchangeCodeForTokens(credential, expectedNonce, redirectUri);
	}

	private boolean isJwt(String value) {
		return value.chars().filter(c -> c == '.').count() == 2;
	}

	/** Troca o code de autorizacao por tokens usando o client_secret gerado na hora. */
	private Optional<TokenValidationResult> exchangeCodeForTokens(String code, String expectedNonce,
			String redirectUri) {
		try {
			String clientSecret = buildClientSecret();
			if (!StringUtils.hasText(clientSecret)) {
				return Optional.empty();
			}
			OAuthProperties.AppleConfig config = properties.getApple();

			Map<String, String> params = new LinkedHashMap<>();
			params.put("code", code);
			params.put("client_id", config.getClientId());
			params.put("client_secret", clientSecret);
			params.put("grant_type", "authorization_code");
			if (StringUtils.hasText(redirectUri)) {
				params.put("redirect_uri", redirectUri);
			}

			String body = params.entrySet().stream()
				.map(entry -> entry.getKey() + "="
					+ URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
				.reduce((left, right) -> left + "&" + right)
				.orElse("");

			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(APPLE_TOKEN_URL))
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

			return verifyIdentityToken(idToken, expectedNonce);
		}
		catch (Exception exception) {
			return Optional.empty();
		}
	}

	/**
	 * client_secret da Apple: JWT ES256 com iss = teamId, sub = clientId (Service ID) e aud = Apple.
	 * Sem isso a troca do code responde 400 invalid_client.
	 */
	private String buildClientSecret() {
		OAuthProperties.AppleConfig config = properties.getApple();
		if (!StringUtils.hasText(config.getTeamId()) || !StringUtils.hasText(config.getKeyId())
				|| !StringUtils.hasText(config.getPrivateKey())) {
			return null;
		}
		try {
			ECKey key = ECKey.parse(normalizePem(config.getPrivateKey()));
			long now = System.currentTimeMillis();
			JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer(config.getTeamId())
				.subject(config.getClientId())
				.audience(APPLE_ISS)
				.issueTime(new Date(now))
				.expirationTime(new Date(now + CLIENT_SECRET_TTL_MS))
				.jwtID(UUID.randomUUID().toString())
				.build();
			SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(config.getKeyId()).build(), claims);
			jwt.sign(new ECDSASigner(key));
			return jwt.serialize();
		}
		catch (Exception exception) {
			return null;
		}
	}

	/** A chave chega por variavel de ambiente: os `\n` literais precisam virar quebras de linha. */
	private String normalizePem(String privateKey) {
		return privateKey.replace("\\n", "\n").trim();
	}

	private Optional<TokenValidationResult> verifyIdentityToken(String identityToken, String expectedNonce) {
		try {
			SignedJWT signedJWT = SignedJWT.parse(identityToken);
			JWSVerifier verifier = new RSASSAVerifier(findRsaKey(signedJWT));

			if (!signedJWT.verify(verifier)) {
				return Optional.empty();
			}

			var claims = signedJWT.getJWTClaimsSet();

			String iss = claims.getIssuer();
			if (!APPLE_ISS.equals(iss)) {
				return Optional.empty();
			}

			String aud = claims.getAudience().stream().findFirst().orElse("");
			String expectedClientId = properties.getApple().getClientId();
			if (!expectedClientId.equals(aud)) {
				return Optional.empty();
			}

			Date expiration = claims.getExpirationTime();
			if (expiration != null && expiration.before(new Date())) {
				return Optional.empty();
			}

			if (StringUtils.hasText(expectedNonce) && !expectedNonce.equals(claims.getStringClaim("nonce"))) {
				return Optional.empty();
			}

			String externalUserId = claims.getSubject();

			String email = claims.getStringClaim("email");
			boolean emailVerified = isTruthy(claims.getClaim("email_verified"));

			String name = null;
			try {
				var userClaim = claims.getJSONObjectClaim("user");
				if (userClaim != null) {
					Object nameObj = userClaim.get("name");
					if (nameObj instanceof java.util.Map nameMap) {
						String firstName = (String) nameMap.get("firstName");
						String lastName = (String) nameMap.get("lastName");
						if (firstName != null) {
							name = (lastName != null) ? firstName + " " + lastName : firstName;
						}
					}
				}
			}
			catch (ParseException ignored) {
				// Sem o claim `user` (que so vem na primeira autorizacao) seguimos sem nome.
			}

			if (!StringUtils.hasText(name)) {
				name = claims.getStringClaim("name");
			}

			return Optional.of(new TokenValidationResult(externalUserId, email, name, emailVerified));
		}
		catch (Exception exception) {
			return Optional.empty();
		}
	}

	private boolean isTruthy(Object claim) {
		return Boolean.TRUE.equals(claim) || "true".equalsIgnoreCase(String.valueOf(claim));
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
			java.net.URL url = URI.create(APPLE_JWKS_URL).toURL();
			jwkSet = JWKSet.load(url);
			jwkFetchTime = System.currentTimeMillis();
		}
	}
}
