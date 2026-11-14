package com.socialcoisa.rede.coin.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.socialcoisa.rede.shared.web.TestDatabaseSupport;

/** O relógio da máquina não pode publicar uma cotação entre cenários de integração. */
@SpringBootTest
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:coin_economy_test_isolation;MODE=MySQL;DB_CLOSE_DELAY=-1" })
class CoinEconomyIsolationTests {

	@MockitoBean private TestDatabaseSupport testDatabaseSupport;

	@Autowired private CoinEconomyService economy;
	@Autowired private CoinRateService rates;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private Clock clock;

	@BeforeEach
	void prepararCotacao() {
		jdbc.update("delete from coin_rate_history");
		rates.latest();
	}

	@Test
	void recalculoPeriodicoNaoPublicaEntreCenarios() {
		jdbc.update("update coin_rate_history set effective_at = ?",
			Timestamp.from(Instant.now(clock).minus(Duration.ofDays(2))));
		var anterior = rates.latest();

		economy.scheduledRecalculate();

		assertThat(rates.latest()).isEqualTo(anterior);
	}

	@Test
	void recalculoMensalNaoPublicaEntreCenarios() {
		var anterior = rates.latest();

		economy.scheduledMonthlyRecalculate();

		assertThat(rates.latest()).isEqualTo(anterior);
	}
}
