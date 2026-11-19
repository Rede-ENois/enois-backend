package com.socialcoisa.rede.identity.application;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** The tokens travel only in HttpOnly cookies (AuthCookieAdvice); the JSON body never carries them. */
public record AuthResponse(@JsonIgnore String token, @JsonIgnore String refreshToken, Instant expiresAt,
		Instant refreshExpiresAt, AuthUserView user, String accessScope,
		@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
		EtapaDoisFatores twoFactor) {

	public AuthResponse(String token, String refreshToken, Instant expiresAt, Instant refreshExpiresAt, AuthUserView user,
			String accessScope) {
		this(token, refreshToken, expiresAt, refreshExpiresAt, user, accessScope, null);
	}

	public AuthResponse(String token, String refreshToken, Instant expiresAt, Instant refreshExpiresAt, AuthUserView user) {
		this(token, refreshToken, expiresAt, refreshExpiresAt, user, "FULL");
	}

	public static AuthResponse aguardaSegundoFator(com.socialcoisa.rede.identity.domain.TwoFactorChallenge desafio) {
		return new AuthResponse(null, null, null, null, null, null, EtapaDoisFatores.from(desafio));
	}

	public record EtapaDoisFatores(String challengeId, String method, Instant expiresAt) {
		public static EtapaDoisFatores from(com.socialcoisa.rede.identity.domain.TwoFactorChallenge desafio) {
			return new EtapaDoisFatores(desafio.id(), desafio.method().name(), desafio.expiresAt());
		}
	}
}
