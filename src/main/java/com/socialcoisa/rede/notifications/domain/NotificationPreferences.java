package com.socialcoisa.rede.notifications.domain;

import java.time.Instant;

/**
 * Push and digest preferences, plus opt-in community project notices. No stored row means
 * {@link #DEFAULTS}: existing categories on, community projects off.
 */
public record NotificationPreferences(boolean weeklyDigest, boolean pushSocial, boolean pushMessages,
		boolean pushGroups, boolean pushJobs, boolean pushSystem, boolean communityProjects, Instant updatedAt) {

	public static final NotificationPreferences DEFAULTS =
		new NotificationPreferences(true, true, true, true, true, true, false, null);

	public boolean allowsPush(String kind) {
		return switch (NotificationCategory.of(kind)) {
			case ALWAYS -> true;
			case SOCIAL -> pushSocial;
			case MESSAGES -> pushMessages;
			case GROUPS -> pushGroups;
			case JOBS -> pushJobs;
			case PROJECTS -> communityProjects;
			case SYSTEM -> pushSystem;
		};
	}

	/** Partial update: a null field keeps the current value. */
	public NotificationPreferences with(Boolean weeklyDigest, Boolean pushSocial, Boolean pushMessages,
			Boolean pushGroups, Boolean pushJobs, Boolean pushSystem, Boolean communityProjects, Instant now) {
		return new NotificationPreferences(
			weeklyDigest != null ? weeklyDigest : this.weeklyDigest,
			pushSocial != null ? pushSocial : this.pushSocial,
			pushMessages != null ? pushMessages : this.pushMessages,
			pushGroups != null ? pushGroups : this.pushGroups,
			pushJobs != null ? pushJobs : this.pushJobs,
			pushSystem != null ? pushSystem : this.pushSystem,
			communityProjects != null ? communityProjects : this.communityProjects,
			now);
	}
}
