package com.socialcoisa.rede.dailygames;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** A fila do prêmio: quem entra, em que ordem, e quanto cada posição leva. */
class DailyGamePayoutTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final Instant T0 = Instant.parse("2026-10-16T12:00:00Z");

    private static JdbcTemplate jdbc(String... migrations) {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var resources = new ClassPathResource[migrations.length];
        for (int i = 0; i < migrations.length; i++) {
            resources[i] = new ClassPathResource(migrations[i]);
        }
        new ResourceDatabasePopulator(resources).execute(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table users (id varchar(255), username varchar(80), name varchar(160))");
        jdbc.update("insert into users values ('u1','ana','Ana'),('u2','bia','Bia'),('u3','caio','Caio'),('u4','dara','Dara')");
        return jdbc;
    }

    private static Timestamp at(long secondsAfterStart) {
        return Timestamp.from(T0.plusSeconds(secondsAfterStart));
    }

    @Test void prizeTiersFollowTheAgreedBands() {
        assertEquals(10_000L, DailyGameRewardsService.prizeFor(1));
        assertEquals(5_000L, DailyGameRewardsService.prizeFor(2));
        assertEquals(5_000L, DailyGameRewardsService.prizeFor(3));
        assertEquals(3_000L, DailyGameRewardsService.prizeFor(4));
        assertEquals(3_000L, DailyGameRewardsService.prizeFor(10));
        assertEquals(1_000L, DailyGameRewardsService.prizeFor(11));
    }

    @Test void wordPayoutRanksByAttemptsAndExcludesThoseWhoMissed() {
        JdbcTemplate jdbc = jdbc("db/migration/V86__daily_word_games.sql");
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        DailyWordService service = new DailyWordService(jdbc, clock);
        jdbc.update("insert into daily_word_challenges values (?,?)", Date.valueOf(DAY), "AMORA");
        service.start("u1"); service.start("u2"); service.start("u3");
        service.guess("u1", "AMIGO", "request_0001", null);
        service.guess("u1", "AMORA", "request_0002", null);
        service.guess("u2", "AMORA", "request_0003", null);
        for (String guess : List.of("AMIGO", "BARCO", "CAMPO", "CANTO", "CARRO", "CASAS")) {
            service.guess("u3", guess, "request_u3_" + guess, null);
        }
        assertEquals("LOST", service.state("u3", null).status());
        assertEquals(List.of("u2", "u1"), service.payoutOrder(DAY));
    }

    @Test void mazePayoutRanksByTimeThenMovesAndExcludesTheStillLost() {
        JdbcTemplate jdbc = jdbc("db/migration/V87__daily_maze_games.sql");
        jdbc.update("insert into daily_maze_challenges values (?,12345)", Date.valueOf(DAY));
        jdbc.update("insert into daily_maze_sessions values (?,?,?,4,?,?)", Date.valueOf(DAY), "u1", 63, at(0), at(30));
        jdbc.update("insert into daily_maze_sessions values (?,?,?,6,?,?)", Date.valueOf(DAY), "u2", 63, at(0), at(30));
        jdbc.update("insert into daily_maze_sessions values (?,?,?,2,?,null)", Date.valueOf(DAY), "u3", 10, at(0));
        assertEquals(List.of("u1", "u2"), new DailyMazeService(jdbc, Clock.fixed(T0, ZoneOffset.UTC)).payoutOrder(DAY));
    }

    @Test void blocksPayoutRanksByScoreThenTimeAndExcludesNonWinners() {
        JdbcTemplate jdbc = jdbc("db/migration/V88__daily_blocks_games.sql");
        jdbc.update("insert into daily_blocks_challenges values (?,12345,3,1)", Date.valueOf(DAY));
        jdbc.update("insert into daily_blocks_sessions values (?,?,?,null,?,'WON',400,3)", Date.valueOf(DAY), "u1", at(90), at(300));
        jdbc.update("insert into daily_blocks_sessions values (?,?,?,null,?,'WON',400,3)", Date.valueOf(DAY), "u2", at(90), at(200));
        jdbc.update("insert into daily_blocks_sessions values (?,?,?,null,?,'WON',900,3)", Date.valueOf(DAY), "u3", at(90), at(500));
        jdbc.update("insert into daily_blocks_sessions values (?,?,?,null,?,'LOST',999,2)", Date.valueOf(DAY), "u4", at(90), at(600));
        assertEquals(List.of("u3", "u2", "u1"), new DailyBlocksService(jdbc, Clock.fixed(T0, ZoneOffset.UTC)).payoutOrder(DAY));
    }
}
