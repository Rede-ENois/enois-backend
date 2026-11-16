package com.socialcoisa.rede.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Um cadastro pendente não fica para sempre: passado o prazo, a linha sai; a que ainda vale fica.
 */
@SpringBootTest
class PendingSignupCleanupTests {

	private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void expiredPendingSignupsArePurgedAndLiveOnesKept() {
		PendingSignupCleanupWorker worker = new PendingSignupCleanupWorker(jdbc,
			Clock.fixed(NOW, ZoneOffset.UTC), true);
		String liveEmail = uniqueEmail("live");
		String expiredEmail = uniqueEmail("expired");
		insert(liveEmail, NOW.plus(Duration.ofHours(1)));
		insert(expiredEmail, NOW.minus(Duration.ofMinutes(1)));

		int deleted = worker.purge();

		assertThat(deleted).isGreaterThanOrEqualTo(1);
		assertThat(count(expiredEmail)).isZero();
		assertThat(count(liveEmail)).isEqualTo(1);
	}

	@Test
	void aDisabledWorkerLeavesTheRowsAlone() {
		String expiredEmail = uniqueEmail("kept");
		insert(expiredEmail, NOW.minus(Duration.ofHours(2)));

		new PendingSignupCleanupWorker(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), false).run();

		assertThat(count(expiredEmail)).isEqualTo(1);
		jdbc.update("delete from pending_signups where email = ?", expiredEmail);
	}

	private static String uniqueEmail(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@test.invalid";
	}

	private void insert(String email, Instant expiresAt) {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		jdbc.update("insert into pending_signups (id, email, name, birth_date, password_hash, token_hash, "
			+ "expires_at, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
			"pending_" + suffix, email, "Pessoa de teste", java.sql.Date.valueOf("1990-05-10"),
			"hash-de-teste", suffix, Timestamp.from(expiresAt), Timestamp.from(expiresAt.minusSeconds(3600)));
	}

	private int count(String email) {
		Integer rows = jdbc.queryForObject("select count(*) from pending_signups where email = ?", Integer.class, email);
		return rows == null ? 0 : rows;
	}
}
