package com.socialcoisa.rede.dailygames;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import com.socialcoisa.rede.shared.web.ApiException;

class DailyWordServiceTest {
    @Test void repeatedLettersOnlyConsumeAvailableOccurrences() {
        assertArrayEquals(new String[]{"CORRECT","ABSENT","ABSENT","ABSENT","CORRECT"}, WordRules.evaluate("AMORA", "AAAAA"));
        assertArrayEquals(new String[]{"PRESENT","PRESENT","PRESENT","PRESENT","CORRECT"}, WordRules.evaluate("AMORA", "MORAA"));
        assertEquals("MACAS", WordRules.normalize("maçãs"));
    }
    @Test void dailyChallengeIsSharedPrivateAndIdempotent() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
		new ResourceDatabasePopulator(new ClassPathResource("db/migration/V118__daily_word_games.sql")).execute(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table users (id varchar(255), username varchar(80), name varchar(160))");
        jdbc.update("insert into users values ('u1','ana','Ana'),('u2','bia','Bia')");
        var coins = org.mockito.Mockito.mock(com.socialcoisa.rede.coin.application.CoinService.class);
        DailyWordService service = new DailyWordService(jdbc, Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC), coins);
        assertEquals("NOT_STARTED", service.state("u1",null).status());
        assertThrows(ApiException.class, () -> service.guess("u1", "AMIGO", "request_0000", null));
        service.start("u1"); service.start("u2");
        String answer = jdbc.queryForObject("select answer from daily_word_challenges",String.class);
        String wrong = answer.equals("AMIGO") ? "AMORA" : "AMIGO";
        var first = service.guess("u1", wrong, "request_0001", null);
        assertEquals("IN_PROGRESS", first.status());
        org.mockito.Mockito.verify(coins, org.mockito.Mockito.never()).recordPresence("u1");
        assertEquals(first, service.guess("u1", wrong, "request_0001", null));
        assertThrows(ApiException.class, () -> service.guess("u1", answer, "request_0001", null));
        var second = service.guess("u2",answer,"request_0002", null);
        assertEquals("WON",second.status());
        assertEquals(1,second.streak());
        // Finishing the daily game counts as today's presence (streak), even without opening the wallet.
        org.mockito.Mockito.verify(coins).recordPresence("u2");
        assertEquals("WON", service.guess("u1", answer, "request_0003", null).status());
        assertEquals(1,jdbc.queryForObject("select count(*) from daily_word_challenges",Integer.class));
        assertEquals("bia",service.leaderboard().items().get(0).username());
    }
}
