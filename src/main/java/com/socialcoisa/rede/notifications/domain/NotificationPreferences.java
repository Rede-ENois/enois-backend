package com.socialcoisa.rede.notifications.domain;

import java.time.Instant;

/**
 * What reaches a person outside the app. No stored row means {@link #DEFAULTS}: everything on.
 * The in-app inbox is never filtered; these toggles only decide push and the weekly digest.
 */
public record NotificationPreferences(boolean weeklyDigest, boolean pushSocial, boolean pushMessages,
		boolean pushGroups, boolean pushJobs, boolean pushSystem, Instant updatedAt) {

	public static final NotificationPreferences DEFAULTS =
		new NotificationPreferences(true, true, true, true, true, true, null);

	public boolean allowsPush(String kind) {
		return switch (NotificationCategory.of(kind)) {
			case ALWAYS -> true;
			case SOCIAL -> pushSocial;
			case MESSAGES -> pushMessages;
			case GROUPS -> pushGroups;
			case JOBS -> pushJobs;
			case SYSTEM -> pushSystem;
		};
	}

	/** Partial update: a null field keeps the current value. */
	public NotificationPreferences with(Boolean weeklyDigest, Boolean pushSocial, Boolean pushMessages,
			Boolean pushGroups, Boolean pushJobs, Boolean pushSystem, Instant now) {
		return new NotificationPreferences(
			weeklyDigest != null ? weeklyDigest : this.weeklyDigest,
			pushSocial != null ? pushSocial : this.pushSocial,
			pushMessages != null ? pushMessages : this.pushMessages,
			pushGroups != null ? pushGroups : this.pushGroups,
			pushJobs != null ? pushJobs : this.pushJobs,
			pushSystem != null ? pushSystem : this.pushSystem,
			now);
	}
}
