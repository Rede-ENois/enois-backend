package com.socialcoisa.rede.notifications.domain;

import java.time.Instant;

/**
 * Push and digest preferences, opt-in community project notices, and the user-chosen silence for
 * reactions and post comments. No stored row means {@link #DEFAULTS}: existing categories on,
 * everything else off.
 */
public record NotificationPreferences(boolean weeklyDigest, boolean pushSocial, boolean pushMessages,
		boolean pushGroups, boolean pushJobs, boolean pushSystem, boolean communityProjects, boolean muteReactions,
		boolean mutePostComments, Instant updatedAt) {

	public static final NotificationPreferences DEFAULTS =
		new NotificationPreferences(true, true, true, true, true, true, false, false, false, null);

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

	/**
	 * Silêncio completo (caixa e push): o aviso nem nasce. Reações cobre o post e o comentário;
	 * resposta ao próprio comentário e menção seguem porque chamam a pessoa diretamente.
	 */
	public boolean silences(String kind) {
		return switch (kind) {
			case "NEW_REACTION", "COMMENT_REACTION" -> muteReactions;
			case "NEW_COMMENT" -> mutePostComments;
			default -> false;
		};
	}

	/** Partial update: a null field keeps the current value. */
	public NotificationPreferences with(Boolean weeklyDigest, Boolean pushSocial, Boolean pushMessages,
			Boolean pushGroups, Boolean pushJobs, Boolean pushSystem, Boolean communityProjects, Boolean muteReactions,
			Boolean mutePostComments, Instant now) {
		return new NotificationPreferences(
			weeklyDigest != null ? weeklyDigest : this.weeklyDigest,
			pushSocial != null ? pushSocial : this.pushSocial,
			pushMessages != null ? pushMessages : this.pushMessages,
			pushGroups != null ? pushGroups : this.pushGroups,
			pushJobs != null ? pushJobs : this.pushJobs,
			pushSystem != null ? pushSystem : this.pushSystem,
			communityProjects != null ? communityProjects : this.communityProjects,
			muteReactions != null ? muteReactions : this.muteReactions,
			mutePostComments != null ? mutePostComments : this.mutePostComments,
			now);
	}
}
