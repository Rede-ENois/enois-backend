package com.socialcoisa.rede.identity.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O rehash periódico das sessões cruas não pode custar a tabela inteira.
 *
 * <p>{@code auth_sessions} recebe uma linha por login e o worker roda a cada 60s em toda instância.
 * O {@code findAll()} antigo trazia TODAS as linhas — e hidratava as entidades em Java — só para
 * descartar as que já eram hash. A prova aqui é a forma do SQL: a rodada em uma frota saudável não
 * pode emitir nenhum {@code select} de {@code auth_sessions} sem o filtro de tamanho na cláusula
 * WHERE, porque é esse filtro que transforma "a tabela inteira" em "as poucas linhas cruas".</p>
 */
@SpringBootTest
class SessionRehashQueryTests {

	@TestConfiguration
	static class InspectorConfig {

		@Bean
		CountingInspector countingInspector() {
			return new CountingInspector();
		}

		@Bean
		org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer statementInspector(
				CountingInspector countingInspector) {
			return properties -> properties.put(
				org.hibernate.cfg.AvailableSettings.STATEMENT_INSPECTOR, countingInspector);
		}
	}

	/** Conta as instruções SQL da thread do teste (a rodada roda na própria thread). */
	static class CountingInspector implements StatementInspector {

		private final AtomicReference<List<String>> captured =
			new AtomicReference<>(java.util.Collections.synchronizedList(new ArrayList<>()));

		@Override
		public String inspect(String sql) {
			captured.get().add(sql);
			return sql;
		}

		void reset() {
			captured.set(java.util.Collections.synchronizedList(new ArrayList<>()));
		}

		List<String> sql() {
			return List.copyOf(captured.get());
		}
	}

	private static final String USER = "rehash_query_user";

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private SessionTokenHashMigration migration;

	@Autowired
	private CountingInspector inspector;

	@BeforeEach
	void seed() {
		Instant now = Instant.now();
		jdbc.update("delete from auth_sessions where user_id = ?", USER);
		jdbc.update("delete from users where id = ?", USER);
		jdbc.update("""
			insert into users (id, username, name, email, open_to_work, is_mentor, created_at, updated_at)
			values (?, ?, ?, ?, false, false, ?, ?)
			""", USER, USER, "Rehash Query", USER + "@test.com", Timestamp.from(now), Timestamp.from(now));
	}

	@AfterEach
	void cleanup() {
		jdbc.update("delete from auth_sessions where user_id = ?", USER);
		jdbc.update("delete from users where id = ?", USER);
	}

	@Test
	void rehashRoundReadsOnlyRowsStillInClearText() {
		Instant now = Instant.now();
		// Frota saudável: 50 sessões já com hash, como o save de hoje grava.
		for (int i = 0; i < 50; i++) {
			jdbc.update("""
				insert into auth_sessions (token, refresh_token, user_id, created_at, expires_at, refresh_expires_at, ip)
				values (?, ?, ?, ?, ?, ?, ?)
				""", com.socialcoisa.rede.shared.security.Hashes.sha256Hex("access-" + i),
				com.socialcoisa.rede.shared.security.Hashes.sha256Hex("refresh-" + i), USER,
				Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)),
				Timestamp.from(now.plusSeconds(86_400)), "127.0.0.1");
		}

		inspector.reset();
		migration.run(null);

		List<String> selects = inspector.sql().stream()
			.filter(sql -> sql.toLowerCase().contains("from auth_sessions"))
			.toList();
		assertThat(selects)
			.as("a rodada precisa consultar auth_sessions; SQL capturado: " + inspector.sql())
			.isNotEmpty();
		// Nenhuma leitura sem o filtro de tamanho: `select ... from auth_sessions` sem WHERE em
		// length(token) é a varredura completa que existia antes.
		assertThat(selects)
			.as("leitura de auth_sessions sem filtro de token cru: " + selects)
			.allSatisfy(sql -> assertThat(sql.toLowerCase()).contains("length("));
	}

	@Test
	void theDirectedReadStillMigratesTheRawSession() {
		Instant now = Instant.now();
		jdbc.update("""
			insert into auth_sessions (token, refresh_token, user_id, created_at, expires_at, refresh_expires_at, ip)
			values (?, ?, ?, ?, ?, ?, ?)
			""", "legacy-access-9", "legacy-refresh-9", USER, Timestamp.from(now),
			Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now.plusSeconds(86_400)), "127.0.0.1");

		migration.run(null);

		String stored = jdbc.queryForObject("select token from auth_sessions where user_id = ?", String.class, USER);
		assertThat(stored).isEqualTo(com.socialcoisa.rede.shared.security.Hashes.sha256Hex("legacy-access-9"));
	}
}