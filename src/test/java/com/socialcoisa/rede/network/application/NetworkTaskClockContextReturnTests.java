package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;

import com.socialcoisa.rede.shared.web.TestDatabase;
import com.zaxxer.hikari.HikariDataSource;

/** Fecha a base temporal própria e prova a reassociação do helper ao contexto comum em cache. */
class NetworkTaskClockContextReturnTests {

	@SpringBootTest
	@AutoConfigureMockMvc
	static class ContextoCacheado {
	}

	@Test
	void helperVoltaAoContextoCacheadoDepoisDoFechamentoDaBaseTemporal() throws Exception {
		TestContextManager comum = new TestContextManager(ContextoCacheado.class);
		comum.beforeTestClass();
		comum.prepareTestInstance(new ContextoCacheado());
		ApplicationContext cacheado = comum.getTestContext().getApplicationContext();
		JdbcTemplate jdbcComum = cacheado.getBean(JdbcTemplate.class);
		String idComum = "job_clk_" + UUID.randomUUID().toString().substring(0, 20);
		String idIsolado = "job_clk_" + UUID.randomUUID().toString().substring(0, 20);
		TestContextManager isolado = new TestContextManager(NetworkTaskClockBoundaryTests.class);
		boolean fechado = false;
		try {
			inserirPeloHelper(idComum);
			assertThat(existe(jdbcComum, idComum)).isTrue();
			isolado.beforeTestClass();
			isolado.prepareTestInstance(new NetworkTaskClockBoundaryTests());
			ApplicationContext contextoTemporal = isolado.getTestContext().getApplicationContext();
			JdbcTemplate jdbcTemporal = contextoTemporal.getBean(JdbcTemplate.class);
			HikariDataSource pool = contextoTemporal.getBean(HikariDataSource.class);
			assertThat(pool.getJdbcUrl()).contains("network_task_clock_boundary", "DB_CLOSE_DELAY=0");
			assertThat(contextoTemporal.containsBean("relogioDaFixture")).isFalse();
			assertThat(existe(jdbcTemporal, idComum)).isFalse();
			inserirPeloHelper(idIsolado);
			assertThat(existe(jdbcTemporal, idIsolado)).isTrue();
			assertThat(existe(jdbcComum, idIsolado)).isFalse();
			isolado.afterTestClass();
			fechado = true;
			assertThat(pool.isClosed()).isTrue();
			// IFEXISTS não recria a base: o fechamento da última conexão precisa tê-la descartado.
			assertThatThrownBy(() -> {
				try (var connection = DriverManager.getConnection(pool.getJdbcUrl() + ";IFEXISTS=TRUE",
						pool.getUsername(), pool.getPassword())) {
					throw new AssertionError("A base temporal permaneceu aberta");
				}
			}).isInstanceOf(SQLException.class).hasMessageContaining("not found");

			TestContextManager retorno = new TestContextManager(ContextoCacheado.class);
			retorno.prepareTestInstance(new ContextoCacheado());
			assertThat(retorno.getTestContext().getApplicationContext()).isSameAs(cacheado);
			assertThat(cacheado.containsBean("relogioDaFixture")).isFalse();
			TestDatabase.setJobPostedBy(idComum, "owner-apos-retorno");
			assertThat(jdbcComum.queryForObject("select posted_by from jobs where id = ?",
				String.class, idComum)).isEqualTo("owner-apos-retorno");
		}
		finally {
			if (!fechado) {
				isolado.afterTestClass();
			}
			comum.prepareTestInstance(new ContextoCacheado());
			jdbcComum.update("delete from job_skills where job_id = ?", idComum);
			jdbcComum.update("delete from jobs where id = ?", idComum);
			assertThat(existe(jdbcComum, idComum)).isFalse();
			comum.afterTestClass();
		}
	}

	private static void inserirPeloHelper(String id) {
		TestDatabase.insertJob(id, "Vaga sintética de relógio", "Empresa de teste", "Remoto", "FULL_TIME", "MID",
			List.of(), Instant.now());
	}

	private static boolean existe(JdbcTemplate jdbc, String id) {
		return jdbc.queryForObject("select count(*) from jobs where id = ?", Integer.class, id) == 1;
	}
}
