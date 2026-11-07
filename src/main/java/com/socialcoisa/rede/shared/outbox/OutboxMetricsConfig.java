package com.socialcoisa.rede.shared.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registra as metricas do outbox ({@link OutboxMetrics}) na fachada de infraestrutura: o pacote do
 * outbox e package-private por desenho (hexagonal), e o bean precisa enxergar o repositorio de
 * dentro dele mesmo.
 */
@Configuration
class OutboxMetricsConfig {

	@Bean
	OutboxMetrics outboxMetrics(SpringDataOutboxEventJpaRepository outboxRepository) {
		return new OutboxMetrics(outboxRepository);
	}
}
