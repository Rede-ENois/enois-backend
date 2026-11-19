package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

class PerfisReplicaFixtureTests {

	private EmbeddedDatabase database;

	private JdbcTemplate jdbc;

	@BeforeEach
	void preparar() {
		database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
		jdbc = new JdbcTemplate(database);
		jdbc.execute("create table users (id varchar(50) primary key, created_at timestamp not null)");
	}

	@AfterEach
	void encerrar() {
		database.shutdown();
	}

	@Test
	void perfisDoCenarioFicamDepoisDaAncoraFuturaSemAlterarOutroPerfil() throws Exception {
		Instant futuro = Instant.parse("2035-01-01T00:00:00Z");
		inserir("alheio", futuro);
		inserir("primeiro", Instant.EPOCH);
		inserir("segundo", Instant.EPOCH);
		var delegate = mock(TestAuthHelper.class);
		when(delegate.authenticate("um")).thenReturn(new TestAuthHelper.AuthSession("fixture", "fixture", "primeiro"));
		when(delegate.authenticate("dois")).thenReturn(new TestAuthHelper.AuthSession("fixture", "fixture", "segundo"));
		try (var fixture = new PerfisReplicaFixture(delegate, jdbc)) {
			fixture.authenticate("um");
			fixture.authenticate("dois");
			assertThat(data("primeiro")).isEqualTo(futuro.plusSeconds(1));
			assertThat(data("segundo")).isEqualTo(futuro.plusSeconds(2));
			assertThat(data("alheio")).isEqualTo(futuro);
		}
		assertThat(data("primeiro")).isEqualTo(Instant.EPOCH);
		assertThat(data("segundo")).isEqualTo(Instant.EPOCH);
		assertThat(data("alheio")).isEqualTo(futuro);
	}

	@Test
	void restauracaoPreservaDataOriginalMesmoAoReutilizarConta() throws Exception {
		inserir("proprio", Instant.EPOCH);
		var delegate = mock(TestAuthHelper.class);
		when(delegate.authenticate("conta")).thenReturn(new TestAuthHelper.AuthSession("fixture", "fixture", "proprio"));
		try (var fixture = new PerfisReplicaFixture(delegate, jdbc)) {
			fixture.authenticate("conta");
			fixture.authenticate("conta");
			assertThat(data("proprio")).isEqualTo(Instant.EPOCH.plusSeconds(2));
		}
		assertThat(data("proprio")).isEqualTo(Instant.EPOCH);
	}

	private void inserir(String id, Instant data) {
		jdbc.update("insert into users values (?, ?)", id, data);
	}

	private Instant data(String id) {
		return jdbc.queryForObject("select created_at from users where id = ?", Timestamp.class, id).toInstant();
	}
}
