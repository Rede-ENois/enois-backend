package com.socialcoisa.rede.identity.application;

import com.socialcoisa.rede.profile.application.UserProfileView;

public record AuthUserView(String id, String username, String name, String headline, String email) {

	public static AuthUserView from(UserProfileView profile) {
		return new AuthUserView(profile.id(), profile.username(), profile.name(), profile.headline(), profile.email());
	}
}
