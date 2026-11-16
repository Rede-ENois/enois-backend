package com.socialcoisa.rede.profile.application;

import com.socialcoisa.rede.media.application.MediaPublicHost;
import com.socialcoisa.rede.profile.domain.UserProfile;

/** One person in the public connections/followers list of a profile. */
public record ProfileNetworkUserView(String id, String username, String name, String headline, String avatar,
		/** O mesmo endereco da foto, no host deste ambiente (ver {@link MediaPublicHost}). */
		String avatarUrl,
		String avatarFrame, NameStyleView nameStyle) {

	static ProfileNetworkUserView from(UserProfile profile) {
		return from(profile, null);
	}

	static ProfileNetworkUserView from(UserProfile profile, MediaPublicHost media) {
		return new ProfileNetworkUserView(profile.id(), profile.username(), profile.displayWithNickname(),
			profile.headline(), profile.avatar(), media == null ? profile.avatar() : media.localize(profile.avatar()),
			profile.avatarFrame(), NameStyleView.from(profile));
	}
}