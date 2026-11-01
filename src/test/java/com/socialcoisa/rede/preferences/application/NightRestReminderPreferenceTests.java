package com.socialcoisa.rede.preferences.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.shared.web.ApiException;

/** Default ligado, desligar persiste e uma atualização parcial não reativa sem querer. */
@SpringBootTest
class NightRestReminderPreferenceTests {

	private static final String NEW_USER = "night_test_new";
	private static final String OLD_USER = "night_test_old";

	@Autowired
	private PersonalizationService service;

	@Autowired
	private JdbcTemplate jdbc;

	@AfterEach
	void cleanup() {
		jdbc.update("delete from user_preferences where user_id in (?, ?)", NEW_USER, OLD_USER);
	}

	@Test
	void defaultIsOnAndDisablingSticksAcrossPartialUpdates() {
		assertThat(service.of(NEW_USER).nightRestReminder()).isTrue();

		service.update(NEW_USER, null, null, null, false, null, null);
		assertThat(service.of(NEW_USER).nightRestReminder()).isFalse();

		service.update(NEW_USER, List.of("TECNOLOGIA"), null, null, null, null, null);
		assertThat(service.of(NEW_USER).nightRestReminder()).isFalse();
	}

	@Test
	void dismissingPersistsTheNightAndSurvivesOtherPreferenceUpdates() {
		assertThat(service.of(NEW_USER).nightRestDismissedOn()).isNull();

		service.dismissNightRest(NEW_USER, "2026-01-11");
		assertThat(service.of(NEW_USER).nightRestDismissedOn()).isEqualTo(LocalDate.of(2026, 1, 11));

		// Mudar outras preferências (ou o tema) não pode reabrir o popup na mesma noite.
		service.update(NEW_USER, List.of("TECNOLOGIA"), null, null, null, null, null);
		assertThat(service.of(NEW_USER).nightRestDismissedOn()).isEqualTo(LocalDate.of(2026, 1, 11));
	}

	@Test
	void rejectsANightThatIsNotAnIsoDate() {
		assertThatThrownBy(() -> service.dismissNightRest(NEW_USER, "11/01/2026"))
			.isInstanceOf(ApiException.class);
	}

	@Test
	void existingAccountGetsTheDefaultWithoutLosingSavedPreferences() {
		Instant now = Instant.now();
		jdbc.update("""
			insert into user_preferences (user_id, liked_topics, updated_at)
			values (?, ?, ?)
			""", OLD_USER, "TECNOLOGIA", Timestamp.from(now));

		var preference = service.of(OLD_USER);
		assertThat(preference.nightRestReminder()).isTrue();
		assertThat(preference.likedTopics()).containsExactly("TECNOLOGIA");
	}
}
