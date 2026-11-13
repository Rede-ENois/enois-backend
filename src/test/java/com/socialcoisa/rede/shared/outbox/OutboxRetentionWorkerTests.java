package com.socialcoisa.rede.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.socialcoisa.rede.feed.application.port.out.OutboxEventRepository;

/**
 * Retencao da outbox: some o que ja foi concluido depois da janela, e so isso.
 *
 * <p>O worker roda no proprio teste (o agendador fica com intervalo enorme), com janela curta para
 * o cenario caber numa base compartilhada: cada linha nasce com o prefixo unico desta classe, entao
 * nenhuma afirmacao depende de contagem global da tabela.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
		"rede.outbox.retention.enabled=true",
		"rede.outbox.retention.days=1",
		"rede.outbox.retention.batch-size=2",
		"rede.outbox.retention.initial-delay-ms=3600000",
		"rede.outbox.retention.interval-ms=3600000",
		"rede.outbox.worker-initial-delay-ms=3600000",
		"rede.outbox.worker-delay-ms=3600000" })
class OutboxRetentionWorkerTests {

	/**
	 * Mesmo handler de falha do teste do worker: sem ele nao ha como deixar um evento em PENDING para
	 * provar que a retencao nao o toca.
	 */
	@TestConfiguration
	static class FailingHandlerConfig {

		@Bean
		FailingHandler failingHandler() {
			return new FailingHandler();
		}
	}

	static class FailingHandler implements OutboxEventHandler {

		@Override
		public void handle(String eventType, String aggregateId, String payload) {
			if ("TesteSempreFalha".equals(eventType)) {
				throw new IllegalStateException("TesteSempreFalha: falha proposital " + aggregateId);
			}
		}
	}

	@Autowired private OutboxRetentionWorker retention;
	@Autowired private OutboxWorker worker;
	@Autowired private OutboxEventRepository outbox;
	@Autowired private JdbcTemplate jdbc;

	private final String prefix = "retencao_" + UUID.randomUUID().toString().substring(0, 8);

	@Test
	void eventoConcluidoForaDaJanelaSaiComOsRecibos() {
		String antigo = append("TesteSempreOk", 40);
		String recente = append("TesteSempreOk", 0);
		worker.processOne(antigo);
		worker.processOne(recente);

		while (retention.purge() > 0) {
			// drena os lotes: o que interessa e o estado final
		}

		assertThat(exists(antigo)).isFalse();
		assertThat(receiptsOf(antigo)).isZero();
		assertThat(exists(recente)).isTrue();
		assertThat(receiptsOf(recente)).isEqualTo(worker.handlerCount());
	}

	@Test
	void eventoPendenteAntigoNuncaEremovido() {
		String pendente = append("TesteSempreFalha", 40);
		worker.processOne(pendente);
		assertThat(status(pendente)).isEqualTo("PENDING");

		retention.purge();

		assertThat(exists(pendente)).isTrue();
		assertThat(status(pendente)).isEqualTo("PENDING");
	}

	@Test
	void loteLimitaQuantasLinhasSaemPorRodada() {
		for (int i = 0; i < 3; i++) {
			String id = append("TesteSempreOk", 40);
			worker.processOne(id);
		}
		long before = retention.expiredCount();

		int deleted = retention.purge();

		assertThat(deleted).isEqualTo(2);
		assertThat(retention.expiredCount()).isEqualTo(before - 2);
	}

	private String append(String eventType, int daysOld) {
		String aggregateId = prefix + "_" + eventType + "_" + UUID.randomUUID().toString().substring(0, 8);
		outbox.append(eventType, aggregateId, "{\"test\":\"true\"}");
		String id = jdbc.queryForObject("select id from outbox_events where aggregate_id = ?", String.class,
			aggregateId);
		Instant created = Instant.now().minusSeconds(daysOld * 86400L);
		jdbc.update("update outbox_events set created_at = ? where id = ?", Timestamp.from(created), id);
		return id;
	}

	private boolean exists(String eventId) {
		return jdbc.queryForObject("select count(*) from outbox_events where id = ?", Integer.class, eventId) > 0;
	}

	private String status(String eventId) {
		return jdbc.queryForObject("select status from outbox_events where id = ?", String.class, eventId);
	}

	private int receiptsOf(String eventId) {
		return jdbc.queryForObject("select count(*) from processed_event_handlers where event_id = ?",
			Integer.class, eventId);
	}
}
