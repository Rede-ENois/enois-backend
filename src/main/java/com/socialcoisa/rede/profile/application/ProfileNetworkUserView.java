package com.socialcoisa.rede.profile.application;

import com.socialcoisa.rede.profile.domain.UserProfile;

/** One person in the public connections/followers list of a profile. */
public record ProfileNetworkUserView(String id, String username, String name, String headline, String avatar,
		String avatarFrame, NameStyleView nameStyle) {

	static ProfileNetworkUserView from(UserProfile profile) {
		return new ProfileNetworkUserView(profile.id(), profile.username(), profile.displayWithNickname(),
			profile.headline(), profile.avatar(), profile.avatarFrame(), NameStyleView.from(profile));
	}
}
