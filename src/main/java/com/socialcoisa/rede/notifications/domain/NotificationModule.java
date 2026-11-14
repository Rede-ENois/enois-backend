package com.socialcoisa.rede.notifications.domain;

import java.util.Locale;
import java.util.Map;

/**
 * The interface area a notification kind belongs to, so the menu can flag the area with unread
 * notifications. The names match the modules of {@code ContentTaxonomy.MODULES}, plus NETWORK
 * (connections) and OTHER (feed, account and platform kinds, which have no menu area of their own).
 * Kinds are compared case-insensitively.
 */
public enum NotificationModule {
	MESSAGES,
	EVENTS,
	JOBS,
	COMPANIES,
	MENTORING,
	DAILY_GAMES,
	COMMUNITY_GAMES,
	GROUPS,
	STUDY_PAIRS,
	BLOGS,
	NETWORK,
	OTHER;

	private static final Map<String, NotificationModule> BY_KIND = Map.ofEntries(
		Map.entry("MESSAGE", MESSAGES),
		Map.entry("NEW_MESSAGE", MESSAGES),
		Map.entry("NEW_WORK_MESSAGE", MESSAGES),
		Map.entry("CALL_INCOMING", MESSAGES),
		Map.entry("CALL_MISSED", MESSAGES),
		Map.entry("CALL_ENDED", MESSAGES),
		Map.entry("CALL_GROUP_LIVE", GROUPS),
		Map.entry("EVENT", EVENTS),
		Map.entry("EVENT_NEXT_OCCURRENCE", EVENTS),
		Map.entry("JOB", JOBS),
		Map.entry("JOB_APPLICATION_STATUS", JOBS),
		Map.entry("JOB_INTERVIEW", JOBS),
		Map.entry("COMPANY_NEW_OPPORTUNITY", JOBS),
		Map.entry("MICROPROJETO", COMPANIES),
		Map.entry("MENTORING", MENTORING),
		Map.entry("JOGO_DIARIO", DAILY_GAMES),
		Map.entry("NEW_GAME", COMMUNITY_GAMES),
		Map.entry("NEW_BLOG_POST", BLOGS),
		Map.entry("CONNECTION_REQUEST", NETWORK),
		Map.entry("CONNECTION_ACCEPTED", NETWORK));

	public static NotificationModule of(String kind) {
		if (kind == null) {
			return OTHER;
		}
		String normalized = kind.toUpperCase(Locale.ROOT);
		NotificationModule mapped = BY_KIND.get(normalized);
		if (mapped != null) {
			return mapped;
		}
		if (normalized.startsWith("GROUP_") || normalized.startsWith("NEW_GROUP_")) {
			return GROUPS;
		}
		// Éroleplay: a área é a mesma dos grupos — convivência entre participantes de um espaço
		// com mestre. Uma área nova exigiria mudar o menu do front (contrato), e o Éroleplay ainda
		// não tem área própria na navegação.
		if (normalized.startsWith("ROLEPLAY_")) {
			return GROUPS;
		}
		return normalized.startsWith("STUDY_PAIR_") ? STUDY_PAIRS : OTHER;
	}
}
