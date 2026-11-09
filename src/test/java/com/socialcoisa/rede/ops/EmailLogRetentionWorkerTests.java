package com.socialcoisa.rede.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.socialcoisa.rede.ops.application.EmailLogRetentionWorker;
import com.socialcoisa.rede.ops.application.port.out.EmailLogRepository;
import com.socialcoisa.rede.ops.domain.EmailLog;
import com.socialcoisa.rede.ops.domain.EmailLogStatus;

/**
 * Retenção do log de e-mails: o metadado que a janela já cobre sai da tabela.
 *
 * <p>O worker roda no próprio teste (o agendador fica com intervalo enorme), com janela curta para
 * o cenário caber numa base compartilhada: cada linha nasce com prefixo único desta classe e a
 * contagem é por delta, então nenhuma afirmação depende do estado alheio da tabela.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
		"rede.email-log.retention.enabled=true",
		"rede.email-log.retention.days=1",
		"rede.email-log.retention.batch-size=2",
		"rede.email-log.retention.initial-delay-ms=3600000",
		"rede.email-log.retention.interval-ms=3600000" })
class EmailLogRetentionWorkerTests {

	@Autowired private EmailLogRetentionWorker retention;
	@Autowired private EmailLogRepository logs;

	private final String prefix = "el_ret_" + UUID.randomUUID().toString().substring(0, 8);

	@Test
	void linhaForaDaJanelaSaiEDentroFica() {
		long antes = logs.countOlderThan(cutoff());
		String antigo = append(40);
		String recente = append(0);

		while (retention.purge() > 0) {
			// drena os lotes: o que interessa é o estado final
		}

		assertThat(recentCount(recente)).as("o recente fica").isEqualTo(1);
		assertThat(recentCount(antigo)).as("o antigo sai").isZero();
		assertThat(logs.countOlderThan(cutoff())).isEqualTo(antes);
	}

	@Test
	void loteLimitaQuantasLinhasSaemPorRodada() {
		long antes = retention.expiredCount();
		for (int i = 0; i < 3; i++) {
			append(40);
		}

		int deleted = retention.purge();

		assertThat(deleted).isEqualTo(2);
		assertThat(retention.expiredCount()).isEqualTo(antes + 1);
	}

	private Instant cutoff() {
		return Instant.now().minusSeconds(86400L);
	}

	private String append(int daysOld) {
		String id = "email_" + UUID.randomUUID().toString().replace("-", "");
		logs.save(EmailLog.create(id, prefix + "@test.com", "Assunto " + prefix, "SIGNUP",
			EmailLogStatus.SENT, null, Instant.now().minusSeconds(daysOld * 86400L)));
		return id;
	}

	private int recentCount(String id) {
		return logs.findRecent(100).stream().filter(log -> log.id().equals(id)).toList().size();
	}
}
