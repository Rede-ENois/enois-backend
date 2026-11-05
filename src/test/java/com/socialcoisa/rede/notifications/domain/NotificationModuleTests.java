package com.socialcoisa.rede.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class NotificationModuleTests {

	@Test
	void everyKindLandsInItsArea() {
		Map<String, NotificationModule> expected = Map.ofEntries(
			Map.entry("MESSAGE", NotificationModule.MESSAGES),
			Map.entry("NEW_MESSAGE", NotificationModule.MESSAGES),
			Map.entry("NEW_WORK_MESSAGE", NotificationModule.MESSAGES),
			Map.entry("EVENT", NotificationModule.EVENTS),
			Map.entry("EVENT_NEXT_OCCURRENCE", NotificationModule.EVENTS),
			Map.entry("JOB", NotificationModule.JOBS),
			Map.entry("JOB_APPLICATION_STATUS", NotificationModule.JOBS),
			Map.entry("COMPANY_NEW_OPPORTUNITY", NotificationModule.JOBS),
			Map.entry("MICROPROJETO", NotificationModule.COMPANIES),
			Map.entry("MENTORING", NotificationModule.MENTORING),
			Map.entry("JOGO_DIARIO", NotificationModule.DAILY_GAMES),
			Map.entry("NEW_GAME", NotificationModule.COMMUNITY_GAMES),
			Map.entry("GROUP_MEMBER_JOINED", NotificationModule.GROUPS),
			Map.entry("GROUP_JOIN_REQUEST", NotificationModule.GROUPS),
			Map.entry("GROUP_JOIN_APPROVED", NotificationModule.GROUPS),
			Map.entry("GROUP_JOIN_REJECTED", NotificationModule.GROUPS),
			Map.entry("GROUP_MEMBER_REMOVED", NotificationModule.GROUPS),
			Map.entry("GROUP_OWNERSHIP_TRANSFERRED", NotificationModule.GROUPS),
			Map.entry("GROUP_CONTENT_REMOVED", NotificationModule.GROUPS),
			Map.entry("GROUP_STATUS_CHANGED", NotificationModule.GROUPS),
			Map.entry("GROUP_NEW_POST", NotificationModule.GROUPS),
			Map.entry("NEW_GROUP_COMMENT", NotificationModule.GROUPS),
			Map.entry("NEW_GROUP_REPLY", NotificationModule.GROUPS),
			Map.entry("STUDY_PAIR_MATCHED", NotificationModule.STUDY_PAIRS),
			Map.entry("STUDY_PAIR_CHECKIN", NotificationModule.STUDY_PAIRS),
			Map.entry("STUDY_PAIR_ENDED", NotificationModule.STUDY_PAIRS),
			Map.entry("STUDY_PAIR_REMINDER", NotificationModule.STUDY_PAIRS),
			Map.entry("NEW_BLOG_POST", NotificationModule.BLOGS),
			Map.entry("CONNECTION_REQUEST", NotificationModule.NETWORK),
			Map.entry("CONNECTION_ACCEPTED", NotificationModule.NETWORK),
			Map.entry("NEW_COMMENT", NotificationModule.OTHER),
			Map.entry("NEW_REPLY", NotificationModule.OTHER),
			Map.entry("NEW_REACTION", NotificationModule.OTHER),
			Map.entry("COMMENT_REACTION", NotificationModule.OTHER),
			Map.entry("MENTION", NotificationModule.OTHER),
			Map.entry("ANSWER_ACCEPTED", NotificationModule.OTHER),
			Map.entry("SEGURANCA", NotificationModule.OTHER),
			Map.entry("MODERACAO", NotificationModule.OTHER),
			Map.entry("SANCAO", NotificationModule.OTHER),
			Map.entry("RECURSO", NotificationModule.OTHER),
			Map.entry("SUPORTE", NotificationModule.OTHER),
			Map.entry("SISTEMA", NotificationModule.OTHER),
			Map.entry("UNKNOWN_KIND", NotificationModule.OTHER));

		assertThat(expected).allSatisfy((kind, module) -> assertThat(NotificationModule.of(kind))
			.as("kind %s", kind).isEqualTo(module));
	}

	@Test
	void comparesKindsCaseInsensitively() {
		assertThat(NotificationModule.of("new_message")).isEqualTo(NotificationModule.MESSAGES);
		assertThat(NotificationModule.of("group_join_request")).isEqualTo(NotificationModule.GROUPS);
		assertThat(NotificationModule.of("Study_Pair_Matched")).isEqualTo(NotificationModule.STUDY_PAIRS);
		assertThat(NotificationModule.of(null)).isEqualTo(NotificationModule.OTHER);
	}

}
