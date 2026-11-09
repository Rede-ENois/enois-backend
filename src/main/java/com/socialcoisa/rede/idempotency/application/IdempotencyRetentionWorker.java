package com.socialcoisa.rede.idempotency.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.idempotency.adapters.out.persistence.SpringDataIdempotencyKeyRepository;

/**
 * Descarta as chaves de idempotência cuja promessa venceu.
 *
 * <p>A tabela recebe uma linha por comando da plataforma (publicar, reagir, editar, apagar) com o
 * corpo da resposta gravado dentro, e nenhuma rotina nunca removia nada — o {@code expires_at}
 * (24&nbsp;h, gravado na escrita) só dizia quando a chave deixava de valer, e ninguém olhava depois
 * disso. Com 50 mil pessoas o histórico morto é permanente: TEXT de resposta por comando de todos
 * os dias, para sempre.</p>
 *
 * <p>A janela vale para qualquer estado: venceu, sai. Uma chave {@code PROCESSING} tem 24 horas
 * desde a gravação, o mesmo teto do replay; quem chega depois disso não é replay pendente, é
 * comando novo com chave repetida — e repetir chave velha com payload igual tem de funcionar como
 * comando novo, não herdar resposta morta. Cada rodada apaga um lote limitado e o próximo ciclo
 * continua, para não segurar a tabela numa transação longa.</p>
 */
@Component
public class IdempotencyRetentionWorker {

	private static final Logger LOG = LoggerFactory.getLogger(IdempotencyRetentionWorker.class);

	private final SpringDataIdempotencyKeyRepository repository;
	private final Clock clock;
	private final boolean enabled;
	private final Duration retention;
	private final int batchSize;

	public IdempotencyRetentionWorker(SpringDataIdempotencyKeyRepository repository, Clock clock,
			@Value("${rede.idempotency.retention.enabled:true}") boolean enabled,
			@Value("${rede.idempotency.retention.hours:48}") long retentionHours,
			@Value("${rede.idempotency.retention.batch-size:500}") int batchSize) {
		this.repository = repository;
		this.clock = clock;
		this.enabled = enabled;
		this.retention = Duration.ofHours(retentionHours);
		this.batchSize = batchSize;
	}

	@Scheduled(initialDelayString = "${rede.idempotency.retention.initial-delay-ms:300000}",
			fixedDelayString = "${rede.idempotency.retention.interval-ms:1800000}")
	public void run() {
		if (!enabled) {
			return;
		}
		try {
			int deleted = purge();
			if (deleted > 0) {
				LOG.info("Chaves de idempotencia expiradas removidas: {}", deleted);
			}
		}
		catch (RuntimeException failure) {
			LOG.warn("Falha ao remover chaves de idempotencia expiradas: {}", failure.getMessage());
		}
	}

	/** Remove um lote de chaves vencidas além da janela. Devolve quantas saíram (log e teste). */
	@Transactional
	public int purge() {
		List<Long> expired = repository.findIdsByExpiresAtBefore(clock.instant().minus(retention),
			PageRequest.ofSize(batchSize));
		if (expired.isEmpty()) {
			return 0;
		}
		repository.deleteByIdIn(expired);
		return expired.size();
	}

	/** Quantas chaves estão elegíveis agora, sem apagar nada (diagnóstico e teste). */
	public long expiredCount() {
		return repository.countByExpiresAtBefore(clock.instant().minus(retention));
	}
}
