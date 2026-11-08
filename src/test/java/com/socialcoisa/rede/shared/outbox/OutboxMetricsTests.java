package com.socialcoisa.rede.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * As metricas do outbox: a agregacao (backlog = pendentes, pressao = soma das tentativas dos
 * pendentes, total = todas as linhas) e o registro dos tres gauges.
 *
 * <p>Sem contexto Spring: a agregacao e pura e o registry e proprio. A leitura do banco em si e do
 * repositorio, coberto pelos testes de worker e retencao.</p>
 */
class OutboxMetricsTests {

	@Test
	void backlogSoContaPendentesEPressaoSomaAsTentativasDeles() {
		var counts = OutboxMetrics.aggregate(List.of(
			row("PENDING", 7, 12),
			row("PROCESSED", 300, 4),
			row("FAILED", 2, 40)));

		assertThat(counts.pending()).isEqualTo(7);
		assertThat(counts.failedAttempts()).isEqualTo(12);
		assertThat(counts.total()).isEqualTo(309);
	}

	@Test
	void semPendentesNaoHaBacklogNemPressao() {
		var counts = OutboxMetrics.aggregate(List.of(row("PROCESSED", 50, 3)));

		assertThat(counts.pending()).isZero();
		assertThat(counts.failedAttempts()).isZero();
		assertThat(counts.total()).isEqualTo(50);
	}

	@Test
	void tabelaVaziaDaZeroEmTudo() {
		var counts = OutboxMetrics.aggregate(List.of());

		assertThat(counts.pending()).isZero();
		assertThat(counts.failedAttempts()).isZero();
		assertThat(counts.total()).isZero();
	}

	/** Os tres gauges ficam registrados com os nomes combinados para o alerta. */
	@Test
	void gaugesRegistradosComOsNomesEsperados() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		new OutboxMetrics(null).bindTo(registry);

		assertThat(registry.find("rede_outbox_pending").gauge()).isNotNull();
		assertThat(registry.find("rede_outbox_failed_attempts").gauge()).isNotNull();
		assertThat(registry.find("rede_outbox_events_total").gauge()).isNotNull();
	}

	private static SpringDataOutboxEventJpaRepository.StatusCount row(String status, long events, long attempts) {
		return new SpringDataOutboxEventJpaRepository.StatusCount() {

			@Override
			public String getStatus() {
				return status;
			}

			@Override
			public long getEvents() {
				return events;
			}

			@Override
			public long getAttempts() {
				return attempts;
			}
		};
	}
}
