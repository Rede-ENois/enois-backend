package com.socialcoisa.rede.profile.application.port.in;

import java.util.List;

import com.socialcoisa.rede.profile.application.ProfileNetworkUserView;

/** Public list of the people connected to a profile (the "connections" and "followers" dialogs). */
public interface ListProfileNetworkUseCase {

	int MAX_LIMIT = 100;

	/**
	 * @param viewerId the signed-in reader, or null for an anonymous read; their blocks hide people
	 * @param followersOnly only the connections the others sent to this profile
	 */
	List<ProfileNetworkUserView> list(String username, boolean followersOnly, String viewerId, int limit);
}
