package com.socialcoisa.rede.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** V249: the reward lookup and the 24-hour play tally have an index that starts with their filter. */
class LookupIndexMigrationTests {

	@Test
	void rewardAndPlayTallyLookupsAreIndexed() {
		var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
		JdbcTemplate jdbc = new JdbcTemplate(ds);
		new ResourceDatabasePopulator(new ClassPathResource("db/migration/V69__coin_ledger.sql")).execute(ds);
		// game_events as V75 creates it (the rest of V75 needs the earlier games migrations).
		jdbc.execute("create table game_events (id varchar(255) not null, game_id varchar(255) not null, "
			+ "kind varchar(24) not null, user_id varchar(255), created_at timestamp not null, primary key (id))");
		new ResourceDatabasePopulator(new ClassPathResource("db/migration/V249__coin_and_game_event_lookup_indexes.sql"))
			.execute(ds);

		assertThat(columns(jdbc, "IDX_COIN_ENTRIES_KIND_REF")).containsExactly("KIND", "REF_ID");
		assertThat(columns(jdbc, "IDX_GAME_EVENTS_KIND_CREATED")).containsExactly("KIND", "CREATED_AT");
	}

	private static List<String> columns(JdbcTemplate jdbc, String index) {
		return jdbc.queryForList("select column_name from information_schema.index_columns where index_name = ? "
			+ "order by ordinal_position", String.class, index);
	}
}
