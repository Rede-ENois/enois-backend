package com.socialcoisa.rede.network.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Descarta os minutos de métrica de operação que passaram da retenção (§17, V366).
 *
 * <p>A série é pequena — um par (operação, resultado) por minuto —, mas cresce para sempre se ninguém
 * apagar, e o painel só lê a janela recente (teto de um dia na rota). Sem esta varredura, {@code purge}
 * seria código morto com cara de funcionalidade.</p>
 *
 * <p>Roda uma vez por dia, fora do horário de pico, com um lote único: a linha é {@code datetime(3)} e o
 * índice de retenção cobre a faixa, então apagar 30 dias de uma vez é uma varredura de índice, não de
 * tabela. Se o volume um dia justificar lote, o padrão do {@code AuditLogRetentionWorker} está aqui ao
 * lado.</p>
 */
@Component
class StorageOperationMetricsWorker {

	private static final Logger LOG = LoggerFactory.getLogger(StorageOperationMetricsWorker.class);

	private final StorageOperationMetrics metrics;
	private final boolean enabled;

	StorageOperationMetricsWorker(StorageOperationMetrics metrics,
			@Value("${rede.storage.metrics.retention.enabled:true}") boolean enabled) {
		this.metrics = metrics;
		this.enabled = enabled;
	}

	@Scheduled(cron = "${rede.storage.metrics.retention.cron:0 40 4 * * *}")
	public void run() {
		if (!enabled) {
			return;
		}
		int removidas = metrics.purge();
		LOG.debug("storage_metrics: varredura de retencao removeu {} minuto(s)", removidas);
	}
}