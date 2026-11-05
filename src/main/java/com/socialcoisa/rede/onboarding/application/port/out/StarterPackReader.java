package com.socialcoisa.rede.onboarding.application.port.out;

import java.time.LocalDate;
import java.util.List;

/**
 * Candidates for the onboarding starter pack, ranked by the database in one query per kind.
 *
 * Every list puts the matches first (topic or skill) and fills the rest by popularity, so the pack
 * is never empty on a platform that has something to offer.
 */
public interface StarterPackReader {

	/** Communities the user is not in: topic matches first, then by member count. */
	List<CommunityCandidate> communities(String userId, List<String> topics, int limit);

	/** Active groups with open entry the user is not in: topic matches first, then by member count. */
	List<GroupCandidate> openGroups(String userId, List<String> topics, int limit);

	/**
	 * Ids of active, verified, adult accounts with no connection, pending request or block with the
	 * user, and not muted by the user: most shared skills and liked topics first, then by reputation.
	 */
	List<String> people(String userId, List<String> topics, List<String> skills, LocalDate adultBornOnOrBefore,
			int limit);

	record CommunityCandidate(String id, String name, String description, String topic, String avatar,
			int memberCount) {
	}

	record GroupCandidate(String id, String slug, String name, String description, String topic, String avatar,
			int memberCount) {
	}
}
