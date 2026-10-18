package com.socialcoisa.rede.dailygames;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import com.socialcoisa.rede.shared.web.ApiException;

class DailyMazeServiceTest {
    @Test void generatedMazeIsConnectedAndActionsAreValidated() {
        int[] maze=MazeRules.generate(20);
        assertArrayEquals(maze,MazeRules.generate(20));
        int[] parent=new int[81], direction=new int[81]; Arrays.fill(parent,-1); parent[0]=0;
        var queue=new ArrayDeque<Integer>(); queue.add(0);
        String[] names={"UP","RIGHT","DOWN","LEFT"};
        while (!queue.isEmpty()) {
            int cell=queue.remove();
            for (int d=0;d<4;d++) if ((maze[cell]&(1<<d))!=0) {
                int next=MazeRules.move(maze,cell,names[d]);
                if (parent[next]<0) { parent[next]=cell; direction[next]=d; queue.add(next); }
            }
        }
        assertTrue(Arrays.stream(parent).allMatch(p->p>=0));
        assertThrows(IllegalArgumentException.class,()->MazeRules.move(maze,0,"UP"));
    }
    @Test void persistsSharedChallengeAndRejectsInvalidMoves() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V87__daily_maze_games.sql")).execute(ds);
        JdbcTemplate jdbc=new JdbcTemplate(ds);
        jdbc.execute("create table users (id varchar(255), username varchar(80), name varchar(160))");
        var clock=Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"),ZoneOffset.UTC);
        var service=new DailyMazeService(jdbc,clock);
        assertEquals("NOT_STARTED",service.state("a",null).status());
        assertThrows(ApiException.class,()->service.move("a","UP","request_0001",null));
        var first=service.start("a"); var second=service.start("b");
        assertArrayEquals(first.passages(),second.passages());
        assertThrows(ApiException.class,()->service.move("a","UP","request_0001",null));
        int bit=(first.passages()[0]&2)!=0?2:4;
        String direction=bit==2?"RIGHT":"DOWN";
        var moved=service.move("a",direction,"request_0002",null);
        assertEquals(1,moved.moves());
        assertEquals(moved.position(),service.move("a",direction,"request_0002",null).position());
        assertThrows(ApiException.class,()->service.move("a","UP","request_0002",null));
        var nextDay=new DailyMazeService(jdbc,Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"),ZoneOffset.UTC));
        assertEquals(moved.position(),nextDay.state("a",LocalDate.parse("2026-10-18")).position());
        assertThrows(ApiException.class,()->nextDay.state("a",LocalDate.parse("2026-10-17")));
    }
}
