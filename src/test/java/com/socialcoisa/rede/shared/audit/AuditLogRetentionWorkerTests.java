package com.socialcoisa.rede.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Retenção da trilha de auditoria: o registro que passou da janela sai da tabela.
 *
 * <p>O worker roda no próprio teste (o agendador fica com intervalo enorme), com janela curta para
 * o cenário caber numa base compartilhada: cada linha nasce com prefixo único desta classe, então
 * nenhuma afirmação depende da contagem global da tabela.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
		"rede.audit.retention.enabled=true",
		"rede.audit.retention.days=1",
		"rede.audit.retention.batch-size=2",
		"rede.audit.retention.initial-delay-ms=3600000",
		"rede.audit.retention.interval-ms=3600000" })
class AuditLogRetentionWorkerTests {

	@Autowired private AuditLogRetentionWorker retention;
	@Autowired private JdbcTemplate jdbc;

	private final String prefix = "aud_ret_" + UUID.randomUUID().toString().substring(0, 8);

	@Test
	void registroForaDaJanelaSaiDaTabela() {
		String antigo = append(10);
		String recente = append(0);

		while (retention.purge() > 0) {
			// drena os lotes: o que interessa é o estado final
		}

		assertThat(exists(antigo)).as("o registro fora da janela sai").isFalse();
		assertThat(exists(recente)).as("o registro dentro da janela fica").isTrue();
	}

	@Test
	void loteLimitaQuantasLinhasSaemPorRodada() {
		for (int i = 0; i < 3; i++) {
			append(10);
		}
		long before = retention.expiredCount();

		int deleted = retention.purge();

		assertThat(deleted).as("um lote por rodada, nao a tabela inteira").isEqualTo(2);
		assertThat(retention.expiredCount()).as("o resto fica para o proximo ciclo").isEqualTo(before - 2);
	}

	@Test
	void soOsMaisAntigosSaemNoLote() {
		// O lote sai do mais antigo para o mais novo: com 3 elegiveis e lote de 2, o mais novo dos
		// tres continua na tabela. Prova que o corte nao e arbitrario.
		String maisAntigo = append(30);
		String meio = append(20);
		String maisNovo = append(10);

		retention.purge();

		assertThat(exists(maisAntigo)).as("o mais antigo sai primeiro").isFalse();
		assertThat(exists(meio)).isFalse();
		assertThat(exists(maisNovo)).as("o mais novo dos elegiveis espera a proxima rodada").isTrue();
	}

	private String append(int daysOld) {
		String id = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
		Instant createdAt = Instant.now().minusSeconds(daysOld * 86400L);
		jdbc.update("insert into audit_logs (id, actor_id, action, target_id, metadata, created_at) "
				+ "values (?, ?, 'POST_DELETED', ?, '{\"reason\":\"fixture de teste\"}', ?)",
			id, prefix + "_actor", prefix + "_target", java.sql.Timestamp.from(createdAt));
		return id;
	}

	private boolean exists(String id) {
		return jdbc.queryForObject("select count(*) from audit_logs where id = ?", Integer.class, id) > 0;
	}
}
