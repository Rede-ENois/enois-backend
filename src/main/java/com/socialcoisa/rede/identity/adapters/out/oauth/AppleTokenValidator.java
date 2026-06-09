package com.socialcoisa.rede.identity.adapters.out.oauth;

import java.net.URI;
import java.text.ParseException;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
class AppleTokenValidator implements TokenValidator {

	private static final String APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys";
	private static final String APPLE_ISS = "https://appleid.apple.com";

	private final OAuthProperties properties;

	private volatile JWKSet jwkSet;
	private volatile long jwkFetchTime;
	private static final long JWK_CACHE_TTL = 3600_000L;

	AppleTokenValidator(OAuthProperties properties) {
		this.properties = properties;
	}

	@Override
	public Optional<TokenValidationResult> validate(OAuthProvider provider, String authorizationCode) {
		if (provider != OAuthProvider.APPLE || !StringUtils.hasText(authorizationCode)) {
			return Optional.empty();
		}

		if (!properties.isAppleConfigured()) {
			return Optional.empty();
		}

		return verifyIdentityToken(authorizationCode);
	}

	private Optional<TokenValidationResult> verifyIdentityToken(String identityToken) {
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

			String externalUserId = claims.getSubject();

			String email = claims.getStringClaim("email");
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
			} catch (ParseException e) {
			}

			if (!StringUtils.hasText(name)) {
				name = claims.getStringClaim("name");
			}

			return Optional.of(new TokenValidationResult(externalUserId, email, name));
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
			java.net.URL url = URI.create(APPLE_JWKS_URL).toURL();
			jwkSet = JWKSet.load(url);
			jwkFetchTime = System.currentTimeMillis();
		}
	}
}
