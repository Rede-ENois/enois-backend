package com.socialcoisa.rede.notifications.application.port.out;

import java.time.Instant;
import java.util.List;

import com.socialcoisa.rede.notifications.domain.DigestItem;

/** Reads what goes into the weekly digest and records who already got it this week. */
public interface WeeklyDigestRepository {

	/**
	 * Next page of people who may receive this week's digest: verified e-mail, digest not turned
	 * off, not delivered this week and not suspended or banned. Ordered by id, after {@code afterUserId}.
	 */
	List<String> eligibleUserIds(String weekKey, String afterUserId, int limit);

	/** Claims the (user, week) slot. False when it was already claimed: the digest goes once a week. */
	boolean claim(String userId, String weekKey, Instant now);

	int unreadNotifications(String userId, Instant since);

	/** Public posts by others in the person's communities since {@code since}, most engaged first. */
	List<DigestItem> communityPosts(String userId, Instant since, int limit);

	/** Active groups the person belongs to with posts by others since {@code since}, most engaged first. */
	List<DigestItem> activeGroups(String userId, Instant since, int limit);

	/** Open jobs posted since {@code since} sharing a skill with the person, not posted or applied by them. */
	List<DigestItem> matchingJobs(String userId, Instant since, int limit);

	/** Events the person confirmed presence in; the date is free text, so the caller filters by date. */
	List<AttendingEvent> attendingEvents(String userId);

	record AttendingEvent(String id, String title, String date, String time) {
	}
}
