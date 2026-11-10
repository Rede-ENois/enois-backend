package com.socialcoisa.rede.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.idempotency.application.IdempotencyRetentionWorker;
import com.socialcoisa.rede.feed.domain.UserId;

/**
 * Retenção das chaves de idempotência: a chave vencida sai, e a janela é por {@code expires_at}.
 *
 * <p>O worker roda no próprio teste (o agendador fica com intervalo enorme), com janela curta para
 * o cenário caber numa base compartilhada: cada chave nasce com prefixo único desta classe, então
 * nenhuma afirmação depende de contagem global da tabela. Antes deste worker nada apagava — a
 * tabela recebia uma linha por comando com o corpo da resposta dentro e crescia para sempre.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
		"rede.idempotency.retention.enabled=true",
		"rede.idempotency.retention.hours=1",
		"rede.idempotency.retention.batch-size=2",
		"rede.idempotency.retention.initial-delay-ms=3600000",
		"rede.idempotency.retention.interval-ms=3600000" })
class IdempotencyRetentionWorkerTests {

	@Autowired private IdempotencyRetentionWorker retention;
	@Autowired private IdempotencyApplicationService idempotency;
	@Autowired private JdbcTemplate jdbc;

	private final String prefix = "idem_retencao_" + UUID.randomUUID().toString().substring(0, 8);

	@Test
	void chaveVencidaSaiDaTabela() {
		String vencida = append(50);
		String valida = append(0);

		while (retention.purge() > 0) {
			// drena os lotes: o que interessa é o estado final
		}

		assertThat(exists(vencida)).as("a chave que venceu sai").isFalse();
		assertThat(exists(valida)).as("a chave dentro da janela fica").isTrue();
	}

	@Test
	void chaveEmProcessamentoVencidaTambemSai() {
		jdbc.update("insert into idempotency_keys (user_id, method, route, idempotency_key, request_hash, "
				+ "status, created_at, expires_at) values (?, 'POST', '/api/teste', ?, 'hash', 'PROCESSING', ?, ?)",
			prefix + "_proc", prefix + "_key_proc_" + UUID.randomUUID().toString().substring(0, 8),
			Timestamp.from(Instant.now().minusSeconds(7200)), Timestamp.from(Instant.now().minusSeconds(3600)));
		String abandonada = jdbc.queryForObject(
			"select idempotency_key from idempotency_keys where user_id = ? and status = 'PROCESSING'",
			String.class, prefix + "_proc");

		while (retention.purge() > 0) {
			// drena os lotes: o worker apaga por lote e a suite tem chaves vencidas de outras classes
			if (!exists(abandonada)) {
				break;
			}
		}

		assertThat(exists(abandonada)).as("PROCESSING com expires_at vencido não é replay pendente").isFalse();
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

	@Test
	void comandoComChaveVencidaReaparecidaExecutaComoNovo() {
		String vencida = append(50);
		while (retention.purge() > 0) {
			// drena: a chave sai da tabela
		}
		assertThat(exists(vencida)).isFalse();

		// A MESMA chave reaparece depois de vencida: comando novo, não replay de resposta morta.
		var response = idempotency.execute(new UserId(prefix + "_user"), "POST", "/api/teste", vencida, "hash",
			() -> new CommandResponse(201, "novo"));
		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(exists(vencida)).as("a execução nova regrava a chave").isTrue();
	}

	/** Cria uma chave real pelo serviço e envelhece o expires_at; devolve o valor da chave. */
	private String append(long hoursOld) {
		String key = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
		idempotency.execute(new UserId(prefix + "_user"), "POST", "/api/teste", key, "hash",
			() -> new CommandResponse(201, "ok"));
		jdbc.update("update idempotency_keys set expires_at = ? where idempotency_key = ? and user_id = ?",
			Timestamp.from(Instant.now().minusSeconds(hoursOld * 3600L)), key, prefix + "_user");
		return key;
	}

	private boolean exists(String key) {
		return jdbc.queryForObject("select count(*) from idempotency_keys where idempotency_key = ?",
			Integer.class, key) > 0;
	}

}
