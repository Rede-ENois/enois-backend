package com.socialcoisa.rede.notifications.application.port.out;

import java.util.Collection;
import java.util.Map;

import com.socialcoisa.rede.notifications.domain.NotificationPlace;

/**
 * Batch reads of what notifications point at, one query per kind of target. Targets that no longer
 * exist (deleted community or event, closed group) are left out of the maps.
 */
public interface NotificationPlaceRepository {

	/** Community place by community id. */
	Map<String, NotificationPlace> communitiesByIds(Collection<String> communityIds);

	/** Community place by post id, for posts published in a community. */
	Map<String, NotificationPlace> communitiesOfPosts(Collection<String> postIds);

	/** Group place by group slug. */
	Map<String, NotificationPlace> groupsBySlugs(Collection<String> slugs);

	/** The event's date as stored (free text, ISO or dd/MM/yyyy) by event id. */
	Map<String, String> eventDatesByIds(Collection<String> eventIds);
}
