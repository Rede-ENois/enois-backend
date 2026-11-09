package com.socialcoisa.rede.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.socialcoisa.rede.identity.application.AuthSessionRetentionWorker;
import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;

/**
 * Retenção das sessões vencidas: a sessão que já não autentica ninguém sai da tabela.
 *
 * <p>O worker roda no próprio teste (o agendador fica com intervalo enorme), com janela curta para
 * o cenário caber numa base compartilhada: cada sessão nasce com prefixo único desta classe, então
 * nenhuma afirmação depende de contagem global da tabela.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
		"rede.auth.session-retention.enabled=true",
		"rede.auth.session-retention.days=1",
		"rede.auth.session-retention.batch-size=2",
		"rede.auth.session-retention.initial-delay-ms=3600000",
		"rede.auth.session-retention.interval-ms=3600000" })
class AuthSessionRetentionWorkerTests {

	@Autowired private AuthSessionRetentionWorker retention;
	@Autowired private AuthSessionRepository sessions;
	@Autowired private JdbcTemplate jdbc;

	private final String prefix = "sess_ret_" + UUID.randomUUID().toString().substring(0, 8);

	@Test
	void sessaoVencidaSaiDaTabela() {
		String vencida = append(50);
		String valida = append(0);

		while (retention.purge() > 0) {
			// drena os lotes: o que interessa é o estado final
		}

		assertThat(exists(vencida)).as("a sessão vencida sai").isFalse();
		assertThat(exists(valida)).as("a sessão dentro da janela fica").isTrue();
	}

	@Test
	void sessaoRevogadaTambemSaiQuandoVence() {
		// Revogar não é apagar: a linha continua até a validade acabar. Depois dela, sai.
		String revogada = append(50);
		jdbc.update("update auth_sessions set revoked_at = ? where token = ?",
			java.sql.Timestamp.from(Instant.now()), hash(revogada));

		while (retention.purge() > 0) {
			// drena os lotes
		}

		assertThat(exists(revogada)).as("revogada e vencida sai junto").isFalse();
	}

	@Test
	void loteLimitaQuantasLinhasSaemPorRodada() {
		for (int i = 0; i < 3; i++) {
			append(50);
		}
		long before = retention.expiredCount();

		int deleted = retention.purge();

		assertThat(deleted).isEqualTo(2);
		assertThat(retention.expiredCount()).isEqualTo(before - 2);
	}

	/**
	 * Grava uma sessao como o repositorio grava: token e refresh em hash. Logada a {@code daysOld}
	 * dias, com a validade dos 30 dias de refresh a partir dai: criada a 50 dias venceu; criada
	 * agora vale por mais 30.
	 */
	private String append(int daysOld) {
		String token = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
		Instant created = Instant.now().minusSeconds(daysOld * 86400L);
		Instant refreshExpires = created.plusSeconds(30 * 86400L);
		jdbc.update("""
			merge into users (id, username, name, email, open_to_work, is_mentor, created_at, updated_at)
			key(id) values (?, ?, 'Retencao', ?, false, false, ?, ?)
			""", prefix + "_user", prefix + "_user", prefix + "_user@test.com",
			java.sql.Timestamp.from(created), java.sql.Timestamp.from(created));
		jdbc.update("insert into auth_sessions (token, refresh_token, user_id, created_at, expires_at, "
				+ "refresh_expires_at) values (?, ?, ?, ?, ?, ?)",
			hash(token), hash(token + "_r"), prefix + "_user", java.sql.Timestamp.from(created),
			java.sql.Timestamp.from(created), java.sql.Timestamp.from(refreshExpires));
		return token;
	}

	private String hash(String token) {
		return com.socialcoisa.rede.shared.security.Hashes.sha256Hex(token);
	}

	private boolean exists(String token) {
		return jdbc.queryForObject("select count(*) from auth_sessions where token = ?", Integer.class, hash(token)) > 0;
	}
}
