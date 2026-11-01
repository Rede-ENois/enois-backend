package com.socialcoisa.rede.coin.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.coin.domain.UserStreak;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * The streak counts real actions: someone who posts every day but never opens the wallet keeps the
 * streak and the daily presence. The wallet still counts too, and nothing pays twice in a day.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StreakPresenceFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private CoinService coins;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private Clock clock;

	@Test
	void postingWithoutOpeningTheWalletCountsAsPresenceOncePerDay() throws Exception {
		var user = auth("streak_poster");
		LocalDate today = UserStreak.today(clock);

		publish(user, "streak-post-1");
		assertThat(coins.hasReward("presenca:" + user.userId() + ":" + today)).isTrue();
		mockMvc.perform(get("/me/streak").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currentDays").value(1))
			.andExpect(jsonPath("$.longestDays").value(1))
			.andExpect(jsonPath("$.todayDone").value(true))
			.andExpect(jsonPath("$.freezesAvailable").value(1))
			.andExpect(jsonPath("$.lastFrozenDay").doesNotExist());

		// A second action and opening the wallet on the same day pay nothing more.
		publish(user, "streak-post-2");
		JsonNode wallet = wallet(user);
		long presences = 0;
		for (JsonNode entry : wallet.get("entries")) {
			if (entry.get("refId").asText().startsWith("presenca:")) {
				presences++;
			}
		}
		assertThat(presences).isEqualTo(1);
		assertThat(wallet.get("streak").get("currentDays").asInt()).isEqualTo(1);
		assertThat(wallet.get("streak").get("todayDone").asBoolean()).isTrue();
	}

	@Test
	void seventhDayInARowPaysTheStreakBonus() throws Exception {
		var user = auth("streak_bonus");
		LocalDate today = UserStreak.today(clock);
		jdbc.update("insert into user_streaks (user_id, current_days, longest_days, last_day, updated_at)"
			+ " values (?, 6, 6, ?, current_timestamp)", user.userId(), today.minusDays(1));

		publish(user, "streak-bonus-post");

		JsonNode presence = presenceEntry(wallet(user), today, user.userId());
		assertThat(presence.get("message").asText()).isEqualTo("Presença diária (bônus de 7 dias seguidos)");
		mockMvc.perform(get("/me/streak").header("X-Session-Token", user.token()))
			.andExpect(jsonPath("$.currentDays").value(7));
	}

	@Test
	void theBonusIsPaidEverySevenDaysNotEveryDayAfterTheSeventh() throws Exception {
		LocalDate today = UserStreak.today(clock);
		var eighth = auth("streak_day8");
		jdbc.update("insert into user_streaks (user_id, current_days, longest_days, last_day, updated_at)"
			+ " values (?, 7, 7, ?, current_timestamp)", eighth.userId(), today.minusDays(1));
		var fourteenth = auth("streak_day14");
		jdbc.update("insert into user_streaks (user_id, current_days, longest_days, last_day, updated_at)"
			+ " values (?, 13, 13, ?, current_timestamp)", fourteenth.userId(), today.minusDays(1));

		publish(eighth, "streak-day8-post");
		publish(fourteenth, "streak-day14-post");

		assertThat(presenceEntry(wallet(eighth), today, eighth.userId()).get("message").asText())
			.isEqualTo("Presença diária");
		assertThat(presenceEntry(wallet(fourteenth), today, fourteenth.userId()).get("message").asText())
			.isEqualTo("Presença diária (bônus de 14 dias seguidos)");
	}

	@Test
	void aFrozenDayDoesNotCountTowardsTheBonus() throws Exception {
		var user = auth("streak_bonus_freeze");
		LocalDate today = UserStreak.today(clock);
		// 6 days up to the day before yesterday; yesterday is frozen and does not add, so today is day 7.
		jdbc.update("insert into user_streaks (user_id, current_days, longest_days, last_day, updated_at)"
			+ " values (?, 6, 6, ?, current_timestamp)", user.userId(), today.minusDays(2));

		publish(user, "streak-bonus-freeze-post");

		assertThat(presenceEntry(wallet(user), today, user.userId()).get("message").asText())
			.isEqualTo("Presença diária (bônus de 7 dias seguidos)");
	}

	@Test
	void existingPresenceHistoryIsKeptOnTheFirstTouch() throws Exception {
		var user = auth("streak_legacy");
		LocalDate today = UserStreak.today(clock);
		for (int back = 1; back <= 3; back++) {
			coins.reward(user.userId(), 1, "presenca:" + user.userId() + ":" + today.minusDays(back), "Presença diária");
		}
		// Before any new action the read already shows the rebuilt streak (nothing done today yet).
		mockMvc.perform(get("/me/streak").header("X-Session-Token", user.token()))
			.andExpect(jsonPath("$.currentDays").value(3))
			.andExpect(jsonPath("$.todayDone").value(false));

		publish(user, "streak-legacy-post");

		mockMvc.perform(get("/me/streak").header("X-Session-Token", user.token()))
			.andExpect(jsonPath("$.currentDays").value(4))
			.andExpect(jsonPath("$.longestDays").value(4))
			.andExpect(jsonPath("$.todayDone").value(true));
	}

	@Test
	void aMissedYesterdayIsFrozenOnTheNextAction() throws Exception {
		var user = auth("streak_freeze");
		LocalDate today = UserStreak.today(clock);
		jdbc.update("insert into user_streaks (user_id, current_days, longest_days, last_day, updated_at)"
			+ " values (?, 3, 3, ?, current_timestamp)", user.userId(), today.minusDays(2));

		publish(user, "streak-freeze-post");

		mockMvc.perform(get("/me/streak").header("X-Session-Token", user.token()))
			.andExpect(jsonPath("$.currentDays").value(4))
			.andExpect(jsonPath("$.lastFrozenDay").value(today.minusDays(1).toString()))
			.andExpect(jsonPath("$.freezesAvailable").value(
				today.minusDays(1).with(java.time.DayOfWeek.MONDAY).equals(today.with(java.time.DayOfWeek.MONDAY)) ? 0 : 1));
	}

	@Test
	void streakRequiresASession() throws Exception {
		mockMvc.perform(get("/me/streak")).andExpect(status().isUnauthorized());
	}

	private void publish(TestAuthHelper.AuthSession user, String key) throws Exception {
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "INSIGHT", "content", "Aprendi algo novo hoje: " + key, "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated());
	}

	private JsonNode wallet(TestAuthHelper.AuthSession user) throws Exception {
		return objectMapper.readTree(mockMvc.perform(get("/carteira").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}

	private static JsonNode presenceEntry(JsonNode wallet, LocalDate day, String userId) {
		for (JsonNode entry : wallet.get("entries")) {
			if (entry.get("refId").asText().equals("presenca:" + userId + ":" + day)) {
				return entry;
			}
		}
		throw new AssertionError("presence entry missing");
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}
}
