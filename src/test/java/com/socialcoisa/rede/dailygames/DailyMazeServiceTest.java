package com.socialcoisa.rede.dailygames;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.dailygames.domain.PlayPlausibility;
import com.socialcoisa.rede.shared.security.ActionThrottle;
import com.socialcoisa.rede.shared.web.ApiException;

class DailyMazeServiceTest {

	private static final String[] NAMES = { "UP", "RIGHT", "DOWN", "LEFT" };

	private static final Instant T0 = Instant.parse("2026-10-18T12:00:00Z");

	private static JdbcTemplate database() {
		var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
		new ResourceDatabasePopulator(new ClassPathResource("db/migration/V118__daily_word_games.sql"),
			new ClassPathResource("db/migration/V119__daily_maze_games.sql"),
			new ClassPathResource("db/migration/V247__daily_games_integrity_flag.sql")).execute(ds);
		JdbcTemplate jdbc = new JdbcTemplate(ds);
		jdbc.execute("create table users (id varchar(255), username varchar(80), name varchar(160))");
		jdbc.update("insert into users values ('a','ana','Ana'),('b','bia','Bia')");
		return jdbc;
	}

	private static DailyMazeService service(JdbcTemplate jdbc, Clock clock, ActionThrottle throttle) {
		return new DailyMazeService(jdbc, clock, mock(CoinService.class), throttle);
	}

	/** The directions of the only route from the entrance to the exit. */
	private static List<String> route(int[] maze) {
		int[] parent = new int[maze.length];
		int[] direction = new int[maze.length];
		Arrays.fill(parent, -1);
		parent[0] = 0;
		var queue = new ArrayDeque<Integer>();
		queue.add(0);
		while (!queue.isEmpty()) {
			int cell = queue.remove();
			for (int d = 0; d < 4; d++) {
				if ((maze[cell] & (1 << d)) != 0) {
					int next = MazeRules.move(maze, cell, NAMES[d]);
					if (parent[next] < 0) {
						parent[next] = cell;
						direction[next] = d;
						queue.add(next);
					}
				}
			}
		}
		List<String> steps = new ArrayList<>();
		for (int cell = MazeRules.EXIT; cell != 0; cell = parent[cell]) {
			steps.add(NAMES[direction[cell]]);
		}
		Collections.reverse(steps);
		return steps;
	}

	@Test
	void generatedMazeIsConnectedAndActionsAreValidated() {
		int[] maze = MazeRules.generate(20);
		assertArrayEquals(maze, MazeRules.generate(20));
		assertEquals(route(maze).size(), MazeRules.shortestRoute(maze));
		assertThrows(IllegalArgumentException.class, () -> MazeRules.move(maze, 0, "UP"));
	}

	@Test
	void persistsSharedChallengeAndRejectsInvalidMoves() {
		JdbcTemplate jdbc = database();
		var service = service(jdbc, Clock.fixed(T0, ZoneOffset.UTC), new ActionThrottle());
		assertEquals("NOT_STARTED", service.state("a", null).status());
		assertThrows(ApiException.class, () -> service.move("a", "UP", "request_0001", null));
		var first = service.start("a");
		var second = service.start("b");
		assertArrayEquals(first.passages(), second.passages());
		assertThrows(ApiException.class, () -> service.move("a", "UP", "request_0001", null));
		String direction = (first.passages()[0] & 2) != 0 ? "RIGHT" : "DOWN";
		var moved = service.move("a", direction, "request_0002", null);
		assertEquals(1, moved.moves());
		assertEquals(moved.position(), service.move("a", direction, "request_0002", null).position());
		assertThrows(ApiException.class, () -> service.move("a", "UP", "request_0002", null));
		var nextDay = service(jdbc, Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC),
			new ActionThrottle());
		assertEquals(moved.position(), nextDay.state("a", LocalDate.parse("2026-10-18")).position());
		assertThrows(ApiException.class, () -> nextDay.state("a", LocalDate.parse("2026-10-17")));
	}

	@Test
	void runFasterThanAPersonIsFlaggedAndLeftOutOfRankingAndPrizes() {
		JdbcTemplate jdbc = database();
		MutableClock clock = new MutableClock(T0);
		var service = service(jdbc, clock, mock(ActionThrottle.class));
		List<String> steps = route(service.start("a").passages());
		service.start("b");

		int n = 0;
		for (String step : steps) {
			service.move("a", step, "fast_000" + n++, null);
		}
		n = 0;
		for (String step : steps) {
			clock.advance(Duration.ofMillis(PlayPlausibility.MAZE_MILLIS_PER_STEP));
			service.move("b", step, "human_00" + n++, null);
		}

		// The flagged run still ends the game for the player; the flag is stored, not dropped.
		assertEquals("WON", service.state("a", null).status());
		assertEquals(PlayPlausibility.TOO_FAST, flagOf(jdbc, "a"));
		assertNull(flagOf(jdbc, "b"));
		assertEquals(List.of("bia"), service.leaderboard().items().stream().map(DailyMazeService.Entry::username).toList());
		assertEquals(List.of("b"), service.payoutOrder(LocalDate.parse("2026-10-18")));
	}

	@Test
	void movesPerSecondAreCappedPerAccount() {
		JdbcTemplate jdbc = database();
		var service = service(jdbc, Clock.fixed(T0, ZoneOffset.UTC), new ActionThrottle());
		var state = service.start("a");
		String forward = (state.passages()[0] & 2) != 0 ? "RIGHT" : "DOWN";
		String back = forward.equals("RIGHT") ? "LEFT" : "UP";
		for (int i = 0; i < DailyMazeService.MOVES_PER_SECOND; i++) {
			service.move("a", i % 2 == 0 ? forward : back, "burst_00" + i, null);
		}
		ApiException rejected = assertThrows(ApiException.class, () -> service.move("a", forward, "burst_last", null));
		assertEquals(429, rejected.status());
		assertEquals("ACTION_THROTTLED", rejected.code());
		assertEquals(DailyMazeService.MOVES_PER_SECOND, service.state("a", null).moves());
	}

	private static String flagOf(JdbcTemplate jdbc, String user) {
		return jdbc.queryForObject("select integrity_flag from daily_maze_sessions where challenge_date=? and user_id=?",
			String.class, Date.valueOf("2026-10-18"), user);
	}
}
