package com.socialcoisa.rede.shared.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Descarta da outbox o que ja cumpriu a funcao.
 *
 * <p>A tabela recebe uma linha por interacao da plataforma (publicacao, reacao, comentario, save,
 * share, delecao) e nenhuma rotina nunca removia nada: com 50 mil pessoas, o historico morto passa
 * a ser varrido pelo indice de pendentes sem servir para nada.</p>
 *
 * <p>Regras, por desenho:</p>
 * <ul>
 * <li>So sai {@code PROCESSED} mais velho que a janela. {@code PENDING} nunca e tocado, inclusive
 * com {@code attempts > 0}: e fila, nao historico.</li>
 * <li>Os recibos do evento saem na MESMA transacao do evento. Recibo sem evento nao existe aqui, e
 * apagar o recibo de um evento ja concluido nao reabre execucao (o evento nao volta para a fila).</li>
 * <li>Cada rodada apaga um lote limitado e o proximo ciclo continua de onde parou, para nao segurar
 * a tabela numa transacao longa.</li>
 * </ul>
 */
@Component
class OutboxRetentionWorker {

	private static final Logger LOG = LoggerFactory.getLogger(OutboxRetentionWorker.class);

	private final SpringDataOutboxEventJpaRepository outboxRepository;
	private final SpringDataOutboxHandlerReceiptJpaRepository receiptRepository;
	private final Clock clock;
	private final TransactionTemplate transaction;
	private final boolean enabled;
	private final Duration retention;
	private final int batchSize;

	OutboxRetentionWorker(SpringDataOutboxEventJpaRepository outboxRepository,
			SpringDataOutboxHandlerReceiptJpaRepository receiptRepository, Clock clock,
			PlatformTransactionManager transactionManager,
			@Value("${rede.outbox.retention.enabled:true}") boolean enabled,
			@Value("${rede.outbox.retention.days:14}") long retentionDays,
			@Value("${rede.outbox.retention.batch-size:500}") int batchSize) {
		this.outboxRepository = outboxRepository;
		this.receiptRepository = receiptRepository;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
		this.enabled = enabled;
		this.retention = Duration.ofDays(retentionDays);
		this.batchSize = batchSize;
	}

	@Scheduled(initialDelayString = "${rede.outbox.retention.initial-delay-ms:600000}",
			fixedDelayString = "${rede.outbox.retention.interval-ms:3600000}")
	void run() {
		if (!enabled) {
			return;
		}
		try {
			int deleted = purge();
			if (deleted > 0) {
				LOG.info("Eventos concluidos removidos da outbox: {}", deleted);
			}
		}
		catch (RuntimeException failure) {
			LOG.warn("Falha ao remover eventos concluidos da outbox: {}", failure.getMessage());
		}
	}

	/** Remove um lote. Devolve quantos eventos sairam (usado no log e no teste). */
	int purge() {
		List<String> expired = outboxRepository.findIdsByStatusAndCreatedAtBefore("PROCESSED",
			clock.instant().minus(retention), PageRequest.ofSize(batchSize));
		if (expired.isEmpty()) {
			return 0;
		}
		transaction.executeWithoutResult(status -> {
			receiptRepository.deleteByEventIdIn(expired);
			outboxRepository.deleteByIdIn(expired);
		});
		return expired.size();
	}

	/** Quantos eventos estao elegiveis agora, sem apagar nada (uso de diagnostico e de teste). */
	long expiredCount() {
		return outboxRepository.countByStatusAndCreatedAtBefore("PROCESSED", clock.instant().minus(retention));
	}
}
