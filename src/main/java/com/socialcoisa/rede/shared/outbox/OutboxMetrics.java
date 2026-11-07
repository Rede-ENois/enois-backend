package com.socialcoisa.rede.shared.outbox;

import java.util.List;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * O outbox na operacao: backlog, pressao de falha e total. Depois das retentativas com backoff
 * (#507) um handler sistematicamente quebrado so se percebe pela fila crescendo — sem estas
 * metricas o sintoma era uma pessoa notando notificacao faltando.
 *
 * <p>Tres gauges, zero tag por evento ou usuario: cardinalidade fixa. Cada gauge dispara a consulta
 * agregada quando e coletado; com o scrape tipico do Prometheus (15s a 60s) sao tres consultas
 * baratas por coleta, longe de justificar cache.</p>
 */
class OutboxMetrics implements MeterBinder {

	private final SpringDataOutboxEventJpaRepository outboxRepository;

	OutboxMetrics(SpringDataOutboxEventJpaRepository outboxRepository) {
		this.outboxRepository = outboxRepository;
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		Gauge.builder("rede_outbox_pending", this, metrics -> metrics.counts().pending())
			.description("Eventos na fila esperando processamento")
			.register(registry);
		Gauge.builder("rede_outbox_failed_attempts", this, metrics -> metrics.counts().failedAttempts())
			.description("Soma das tentativas falhadas acumuladas pelos eventos pendentes")
			.register(registry);
		Gauge.builder("rede_outbox_events_total", this, metrics -> metrics.counts().total())
			.description("Linhas na outbox (limitadas pela janela de retencao)")
			.register(registry);
	}

	Counts counts() {
		return aggregate(outboxRepository.countByStatusWithAttempts());
	}

	/** Backlog e pressao vem so do status PENDING; o total soma todos os status. */
	static Counts aggregate(List<SpringDataOutboxEventJpaRepository.StatusCount> rows) {
		long pending = 0;
		long attempts = 0;
		long total = 0;
		for (SpringDataOutboxEventJpaRepository.StatusCount row : rows) {
			total += row.getEvents();
			if ("PENDING".equals(row.getStatus())) {
				pending += row.getEvents();
				attempts += row.getAttempts();
			}
		}
		return new Counts(pending, attempts, total);
	}

	record Counts(long pending, long failedAttempts, long total) {
	}
}
