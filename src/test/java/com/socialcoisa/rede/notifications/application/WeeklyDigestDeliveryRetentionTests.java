package com.socialcoisa.rede.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.socialcoisa.rede.notifications.application.port.out.WeeklyDigestRepository;

/**
 * Retenção de {@code digest_deliveries}: a semana que a janela já cobre sai, a semana corrente fica.
 *
 * <p>O worker roda no próprio teste (o agendador fica com intervalo enorme), com janela curta para
 * o cenário caber numa base compartilhada: cada linha nasce com um {@code user_id} único desta
 * classe e as asserções são por delta, então nenhuma afirmação depende do estado alheio da tabela.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
		"rede.digest.retention.enabled=true",
		"rede.digest.retention.weeks=8",
		"rede.digest.retention.batch-size=2",
		"rede.digest.retention.initial-delay-ms=3600000",
		"rede.digest.retention.interval-ms=3600000" })
class WeeklyDigestDeliveryRetentionTests {

	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	@Autowired private WeeklyDigestDeliveryRetentionWorker retention;
	@Autowired private WeeklyDigestRepository digests;
	@Autowired private JdbcTemplate jdbc;

	private final String prefix = "dg_ret_" + UUID.randomUUID().toString().substring(0, 8);

	@Test
	void semanaForaDaJanelaSaiEASemanaCorrenteFica() {
		String antiga = weekKey(40);
		String corrente = weekKey(0);
		append(antiga);
		append(corrente);

		while (retention.purge() > 0) {
			// drena os lotes: o que interessa é o estado final
		}

		assertThat(rowsOf(corrente)).as("a semana corrente fica").isEqualTo(1);
		assertThat(rowsOf(antiga)).as("a semana de 40 semanas atrás sai").isZero();
	}

	@Test
	void loteLimitaQuantasSemanasSaemPorRodada() {
		// Base limpa de resíduo de outra rodada: o que se mede é o efeito deste lote.
		while (retention.purge() > 0) {
			// drena
		}
		String primeira = weekKey(30);
		String segunda = weekKey(31);
		String terceira = weekKey(32);
		append(primeira);
		append(segunda);
		append(terceira);

		long antes = retention.expiredCount();
		retention.purge();

		// batch-size=2: saem no máximo duas semanas por rodada, e a terceira espera o próximo ciclo
		assertThat(antes).isGreaterThanOrEqualTo(3);
		assertThat(retention.expiredCount()).isEqualTo(antes - 2);
		assertThat(rowsOf(primeira) + rowsOf(segunda) + rowsOf(terceira)).isGreaterThanOrEqualTo(1);
	}

	@Test
	void semanaDentroDaJanelaNuncaElegivel() {
		String dentro = weekKey(7);
		append(dentro);

		assertThat(digests.weekKeysBefore(retention.cutoffWeekKey(), Integer.MAX_VALUE)).doesNotContain(dentro);
		assertThat(rowsOf(dentro)).isEqualTo(1);
	}

	private String weekKey(int weeksAgo) {
		LocalDate day = LocalDate.now(ZONE).minusWeeks(weeksAgo);
		return "%d-W%02d".formatted(day.get(IsoFields.WEEK_BASED_YEAR),
			day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
	}

	private void append(String weekKey) {
		jdbc.update("insert into digest_deliveries (user_id, week_key, sent_at) values (?, ?, ?)",
			prefix + "_" + UUID.randomUUID().toString().substring(0, 8), weekKey, Timestamp.from(Instant.now()));
	}

	private int rowsOf(String weekKey) {
		Integer count = jdbc.queryForObject(
			"select count(*) from digest_deliveries where user_id like ? and week_key = ?", Integer.class,
			prefix + "%", weekKey);
		return count == null ? 0 : count;
	}
}
