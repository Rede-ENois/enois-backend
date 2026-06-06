package com.socialcoisa.rede.profile.application;

import java.util.List;

import com.socialcoisa.rede.profile.domain.UserProfile;

public record UserProfileView(String id, String username, String name, String headline, String email, String company,
		String location, String avatar, String banner, String bio, List<String> skills, List<String> goals,
		String availability, String portfolio, List<String> externalLinks, boolean openToWork, boolean isMentor,
		int connectionsCount, int followersCount, int reputation) {

	public static UserProfileView from(UserProfile profile) {
		return new UserProfileView(profile.id(), profile.username(), profile.name(), profile.headline(),
			profile.email(), profile.company(), profile.location(), profile.avatar(), profile.banner(), profile.bio(),
			profile.skills(), profile.goals(), profile.availability(), profile.portfolio(), profile.externalLinks(),
			profile.openToWork(), profile.mentor(), profile.connectionsCount(), profile.followersCount(),
			profile.reputation());
	}
}
