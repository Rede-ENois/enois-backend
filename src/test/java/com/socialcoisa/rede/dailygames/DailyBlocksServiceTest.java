package com.socialcoisa.rede.dailygames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.dailygames.blocks.BlocksEngine;

/** The state reveals a short window of upcoming pieces, and the window follows the game. */
class DailyBlocksServiceTest {

	@Test
	void revealsOnlyTheNextWindowOfPieces() {
		var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
		new ResourceDatabasePopulator(new ClassPathResource("db/migration/V120__daily_blocks_games.sql")).execute(ds);
		JdbcTemplate jdbc = new JdbcTemplate(ds);
		MutableClock clock = new MutableClock(Instant.parse("2026-10-18T12:00:00Z"));
		var service = new DailyBlocksService(jdbc, clock, mock(CoinService.class));

		var started = service.start("u1");
		long seed = jdbc.queryForObject("select seed from daily_blocks_challenges", Long.class);
		assertEquals(DailyBlocksService.PIECES_AHEAD, started.pieces().size());
		assertEquals(BlocksEngine.pieceSequence(seed, DailyBlocksService.PIECES_AHEAD, 1), started.pieces());

		clock.advance(Duration.ofMillis(200));
		var dropped = service.action("u1", "HARD_DROP", "request_0001", null);
		assertEquals("PLAYING", dropped.status());
		assertEquals(BlocksEngine.pieceSequence(seed, DailyBlocksService.PIECES_AHEAD, 2), dropped.pieces());
	}
}
