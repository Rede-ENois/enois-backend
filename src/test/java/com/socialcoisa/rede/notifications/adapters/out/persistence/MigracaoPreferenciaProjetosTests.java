package com.socialcoisa.rede.notifications.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Prova aditiva em H2; a compatibilidade MariaDB exige a validação isolada adicional. */
class MigracaoPreferenciaProjetosTests {

	@Test
	void migracaoPreservaEscolhasExistentesEExigeAdesaoExplicita() throws Exception {
		var fonte = new DriverManagerDataSource("jdbc:h2:mem:preferencia_projetos_" + UUID.randomUUID(), "sa", "");
		try (var conexao = fonte.getConnection()) {
			var jdbc = new JdbcTemplate(fonte);
			jdbc.execute("""
				create table notification_preferences (
				user_id varchar(255) primary key, weekly_digest boolean not null default true,
				push_social boolean not null default true, updated_at timestamp not null)""");
			jdbc.update("insert into notification_preferences values ('legado676', false, false, current_timestamp)");
			ScriptUtils.executeSqlScript(conexao, new FileSystemResource(
				"src/main/resources/db/migration/V292__preferencia_projetos_comunidade.sql"));
			assertThat(jdbc.queryForMap("select weekly_digest, push_social, community_projects from notification_preferences where user_id = 'legado676'"))
				.containsEntry("WEEKLY_DIGEST", false).containsEntry("PUSH_SOCIAL", false).containsEntry("COMMUNITY_PROJECTS", false);
			jdbc.update("insert into notification_preferences (user_id, updated_at) values ('novo676', current_timestamp)");
			assertThat(jdbc.queryForObject("select community_projects from notification_preferences where user_id = 'novo676'", Boolean.class))
				.isFalse();
			assertThatThrownBy(() -> jdbc.update("update notification_preferences set community_projects = null where user_id = 'novo676'"))
				.isInstanceOf(DataIntegrityViolationException.class);
			assertThat(jdbc.queryForList("""
				select column_name from information_schema.index_columns
				where index_name = 'IDX_NOTIFICATION_PREFERENCES_COMMUNITY_PROJECTS' order by ordinal_position""", String.class))
				.containsExactly("COMMUNITY_PROJECTS", "USER_ID");
		}
	}
}
