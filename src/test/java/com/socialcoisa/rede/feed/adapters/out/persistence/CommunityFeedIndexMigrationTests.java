package com.socialcoisa.rede.feed.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@SpringBootTest
class CommunityFeedIndexMigrationTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private DataSource dataSource;

	@Test
	void indiceDoFeedDaComunidadeTemOrdemCorretaEAceitaReexecucao() {
		assertThat(colunasDoIndice()).containsExactly("COMMUNITY_ID", "STATUS", "CHANNEL_ID", "CREATED_AT");

		new ResourceDatabasePopulator(
			new ClassPathResource("db/migration/V351__community_feed_index.sql")).execute(dataSource);

		assertThat(colunasDoIndice()).containsExactly("COMMUNITY_ID", "STATUS", "CHANNEL_ID", "CREATED_AT");
	}

	private List<String> colunasDoIndice() {
		return jdbc.queryForList("""
			select column_name from information_schema.index_columns
			where table_name = 'POSTS' and index_name = 'IDX_POSTS_COMMUNITY_FEED'
			order by ordinal_position
			""", String.class);
	}
}
