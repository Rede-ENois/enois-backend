package com.socialcoisa.rede.notifications.domain;

import java.util.Map;

/**
 * The push category of each notification kind: the single place that decides which preference
 * toggle a kind obeys. A new kind must be added here (NotificationCategoryTests fails otherwise);
 * an unmapped kind falls back to SYSTEM, so it still respects one of the person's toggles.
 */
public enum NotificationCategory {

	/** Reactions, comments, replies, mentions and connections. */
	SOCIAL,
	MESSAGES,
	/** Groups, blogs and games. */
	GROUPS,
	JOBS,
	/** Support, microprojects and platform notices. */
	SYSTEM,
	/** Security, moderation and sanctions: always pushed, never configurable. */
	ALWAYS;

	private static final Map<String, NotificationCategory> BY_KIND = Map.ofEntries(
		Map.entry("SEGURANCA", ALWAYS),
		Map.entry("MODERACAO", ALWAYS),
		Map.entry("SANCAO", ALWAYS),
		Map.entry("RECURSO", ALWAYS),
		Map.entry("CONNECTION_REQUEST", SOCIAL),
		Map.entry("CONNECTION_ACCEPTED", SOCIAL),
		Map.entry("NEW_COMMENT", SOCIAL),
		Map.entry("NEW_REPLY", SOCIAL),
		Map.entry("NEW_REACTION", SOCIAL),
		Map.entry("COMMENT_REACTION", SOCIAL),
		Map.entry("MENTION", SOCIAL),
		Map.entry("NEW_MESSAGE", MESSAGES),
		Map.entry("GROUP_MEMBER_JOINED", GROUPS),
		Map.entry("GROUP_JOIN_REQUEST", GROUPS),
		Map.entry("GROUP_JOIN_APPROVED", GROUPS),
		Map.entry("GROUP_JOIN_REJECTED", GROUPS),
		Map.entry("GROUP_MEMBER_REMOVED", GROUPS),
		Map.entry("GROUP_OWNERSHIP_TRANSFERRED", GROUPS),
		Map.entry("GROUP_CONTENT_REMOVED", GROUPS),
		Map.entry("GROUP_STATUS_CHANGED", GROUPS),
		Map.entry("NEW_GROUP_COMMENT", GROUPS),
		Map.entry("NEW_GROUP_REPLY", GROUPS),
		Map.entry("NEW_BLOG_POST", GROUPS),
		Map.entry("NEW_GAME", GROUPS),
		Map.entry("JOGO_DIARIO", GROUPS),
		Map.entry("JOB_APPLICATION_STATUS", JOBS),
		Map.entry("SUPORTE", SYSTEM),
		Map.entry("MICROPROJETO", SYSTEM),
		Map.entry("SISTEMA", SYSTEM),
		// Kinds from sibling PRs, mapped ahead so the merge order does not matter:
		// ANSWER_ACCEPTED (#336), EVENT_NEXT_OCCURRENCE (#337), STUDY_PAIR_* (#338).
		Map.entry("ANSWER_ACCEPTED", SOCIAL),
		Map.entry("EVENT_NEXT_OCCURRENCE", GROUPS),
		Map.entry("STUDY_PAIR_MATCHED", SOCIAL),
		Map.entry("STUDY_PAIR_CHECKIN", SOCIAL),
		Map.entry("STUDY_PAIR_ENDED", SOCIAL),
		Map.entry("STUDY_PAIR_REMINDER", SOCIAL));

	public static NotificationCategory of(String kind) {
		return kind == null ? SYSTEM : BY_KIND.getOrDefault(kind, SYSTEM);
	}

	public static boolean isMapped(String kind) {
		return BY_KIND.containsKey(kind);
	}
}
