package com.socialcoisa.rede.ops.application;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.ops.application.port.out.EmailLogRepository;

/**
 * Descarta do log de e-mails as linhas que a janela de retenção já cobre.
 *
 * <p>O log grava metadados de cada e-mail enviado (verificação de cadastro, reset de senha,
 * digest) — uma linha por envio, com o endereço da pessoa dentro. Serve para acompanhar entrega e
 * volume; a janela que o painel enxerga é de uma dúzia de linhas. Sem retenção, os metadados de
 * quem recebeu o quê ficam para sempre, crescendo por e-mail de todos os dias e guardando dado
 * pessoal além do tempo com função — exatamente o que a própria doc do log diz que não quer.</p>
 *
 * <p>Cada rodada apaga um lote limitado e o próximo ciclo continua, para não segurar a tabela
 * numa transação longa (mesmo formato da retenção da outbox e das chaves de idempotência).</p>
 */
@Component
public class EmailLogRetentionWorker {

	private static final Logger LOG = LoggerFactory.getLogger(EmailLogRetentionWorker.class);

	private final EmailLogRepository logs;
	private final Clock clock;
	private final boolean enabled;
	private final Duration retention;
	private final int batchSize;

	public EmailLogRetentionWorker(EmailLogRepository logs, Clock clock,
			@Value("${rede.email-log.retention.enabled:true}") boolean enabled,
			@Value("${rede.email-log.retention.days:30}") long retentionDays,
			@Value("${rede.email-log.retention.batch-size:500}") int batchSize) {
		this.logs = logs;
		this.clock = clock;
		this.enabled = enabled;
		this.retention = Duration.ofDays(retentionDays);
		this.batchSize = batchSize;
	}

	@Scheduled(initialDelayString = "${rede.email-log.retention.initial-delay-ms:300000}",
			fixedDelayString = "${rede.email-log.retention.interval-ms:3600000}")
	public void run() {
		if (!enabled) {
			return;
		}
		try {
			int deleted = purge();
			if (deleted > 0) {
				LOG.info("Linhas do log de e-mail fora da janela de retencao removidas: {}", deleted);
			}
		}
		catch (RuntimeException failure) {
			LOG.warn("Falha ao remover linhas do log de e-mail: {}", failure.getMessage());
		}
	}

	/** Remove um lote de linhas fora da janela. Devolve quantas saíram (log e teste). */
	@Transactional
	public int purge() {
		return logs.deleteOlderThan(clock.instant().minus(retention), batchSize);
	}

	/** Quantas linhas estão elegíveis agora, sem apagar nada (diagnóstico e teste). */
	public long expiredCount() {
		return logs.countOlderThan(clock.instant().minus(retention));
	}
}
