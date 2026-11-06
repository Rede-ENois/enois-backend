package com.socialcoisa.rede.dailygames;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.dailygames.domain.PlayPlausibility;
import com.socialcoisa.rede.shared.web.ApiException;

class DailyWordServiceTest {

	private static JdbcTemplate database() {
		DriverManagerDataSource ds = new DriverManagerDataSource(
			"jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
		new ResourceDatabasePopulator(new ClassPathResource("db/migration/V118__daily_word_games.sql"),
			new ClassPathResource("db/migration/V119__daily_maze_games.sql"),
			new ClassPathResource("db/migration/V247__daily_games_integrity_flag.sql")).execute(ds);
		JdbcTemplate jdbc = new JdbcTemplate(ds);
		jdbc.execute("create table users (id varchar(255), username varchar(80), name varchar(160))");
		jdbc.update("insert into users values ('u1','ana','Ana'),('u2','bia','Bia')");
		return jdbc;
	}

	@Test
	void repeatedLettersOnlyConsumeAvailableOccurrences() {
		assertArrayEquals(new String[] { "CORRECT", "ABSENT", "ABSENT", "ABSENT", "CORRECT" },
			WordRules.evaluate("AMORA", "AAAAA"));
		assertArrayEquals(new String[] { "PRESENT", "PRESENT", "PRESENT", "PRESENT", "CORRECT" },
			WordRules.evaluate("AMORA", "MORAA"));
		assertEquals("MACAS", WordRules.normalize("maçãs"));
	}

	@Test
	void dailyChallengeIsSharedPrivateAndIdempotent() {
		JdbcTemplate jdbc = database();
		var coins = mock(CoinService.class);
		MutableClock clock = new MutableClock(Instant.parse("2026-10-18T12:00:00Z"));
		DailyWordService service = new DailyWordService(jdbc, clock, coins);
		assertEquals("NOT_STARTED", service.state("u1", null).status());
		assertThrows(ApiException.class, () -> service.guess("u1", "AMIGO", "request_0000", null));
		service.start("u1");
		service.start("u2");
		clock.advance(Duration.ofSeconds(5));
		String answer = jdbc.queryForObject("select answer from daily_word_challenges", String.class);
		String wrong = answer.equals("AMIGO") ? "AMORA" : "AMIGO";
		var first = service.guess("u1", wrong, "request_0001", null);
		assertEquals("IN_PROGRESS", first.status());
		verify(coins, never()).recordPresence("u1");
		assertEquals(first, service.guess("u1", wrong, "request_0001", null));
		assertThrows(ApiException.class, () -> service.guess("u1", answer, "request_0001", null));
		var second = service.guess("u2", answer, "request_0002", null);
		assertEquals("WON", second.status());
		assertEquals(1, second.streak());
		// Finishing the daily game counts as today's presence (streak), even without opening the wallet.
		verify(coins).recordPresence("u2");
		assertEquals("WON", service.guess("u1", answer, "request_0003", null).status());
		assertEquals(1, jdbc.queryForObject("select count(*) from daily_word_challenges", Integer.class));
		assertEquals("bia", service.leaderboard().items().get(0).username());
	}

	@Test
	void winFasterThanTypingIsFlaggedAndLeftOutOfRankingAndPrizes() {
		JdbcTemplate jdbc = database();
		MutableClock clock = new MutableClock(Instant.parse("2026-10-18T12:00:00Z"));
		DailyWordService service = new DailyWordService(jdbc, clock, mock(CoinService.class));
		service.start("u1");
		service.start("u2");
		String answer = jdbc.queryForObject("select answer from daily_word_challenges", String.class);

		clock.advance(Duration.ofMillis(PlayPlausibility.WORD_MILLIS_PER_GUESS - 1));
		assertEquals("WON", service.guess("u1", answer, "request_fast", null).status());
		clock.advance(Duration.ofMillis(1));
		assertEquals("WON", service.guess("u2", answer, "request_human", null).status());

		assertEquals(PlayPlausibility.TOO_FAST, flagOf(jdbc, "u1"));
		assertNull(flagOf(jdbc, "u2"));
		assertEquals(List.of("bia"), service.leaderboard().items().stream().map(DailyWordService.Entry::username).toList());
		assertEquals(List.of("u2"), service.payoutOrder(LocalDate.parse("2026-10-18")));
	}

	private static String flagOf(JdbcTemplate jdbc, String user) {
		return jdbc.queryForObject("select integrity_flag from daily_word_sessions where challenge_date=? and user_id=?",
			String.class, Date.valueOf("2026-10-18"), user);
	}
}
