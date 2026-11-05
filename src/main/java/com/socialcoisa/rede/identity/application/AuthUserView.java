package com.socialcoisa.rede.identity.application;

import com.socialcoisa.rede.profile.domain.UserProfile;

/**
 * Usuario autenticado (login/signup/refresh e {@code /auth/me}). Inclui o email por ser dado do
 * proprio usuario; nunca deve ser montado a partir de uma projecao publica de terceiros.
 */
public record AuthUserView(String id, String username, String name, String headline, String email,
		boolean emailVerified, boolean twoFactorEnabled, boolean identityVerified, boolean hasPassword,
		boolean onboardingCompleted) {

	public static AuthUserView from(UserProfile profile, boolean hasPassword) {
		return new AuthUserView(profile.id(), profile.username(), profile.name(), profile.headline(), profile.email(),
			profile.emailVerified(), profile.twoFactorEnabled(), profile.identityVerified(), hasPassword,
			profile.onboardingCompleted());
	}
}