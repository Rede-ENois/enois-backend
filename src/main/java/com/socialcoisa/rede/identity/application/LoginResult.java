package com.socialcoisa.rede.identity.application;

import java.time.Instant;

public record LoginResult(AuthResponse session, TwoFactorChallengeView challenge, String resetToken) {

	public static LoginResult authenticated(AuthResponse session) {
		return new LoginResult(session, null, null);
	}

	public static LoginResult twoFactorRequired(String challengeId, String method, Instant expiresAt) {
		return new LoginResult(null, new TwoFactorChallengeView(challengeId, method, expiresAt), null);
	}

	public static LoginResult passwordExpired(String resetToken) {
		return new LoginResult(null, null, resetToken);
	}

	public boolean requiresTwoFactor() {
		return challenge != null;
	}

	public boolean requiresPasswordReset() {
		return resetToken != null;
	}

	public record TwoFactorChallengeView(String challengeId, String method, Instant expiresAt) {
	}
}
