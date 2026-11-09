package com.socialcoisa.rede.shared.audit;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Descarta da trilha de auditoria o que já passou da janela de retenção.
 *
 * <p>A tabela recebe uma linha por ação de moderação ou de dono de conteúdo — remoção de post,
 * comentário, grupo, restauração — com o <b>motivo escrito à mão</b> no {@code metadata} (o texto
 * que o moderador digitou ao remover). É a única tabela de crescimento que não tinha um único
 * {@code delete}: a janela existia só como intenção, então cada remoção de todos os dias ficava
 * para sempre, com texto livre de pessoa dentro.</p>
 *
 * <p>Regras, por desenho:</p>
 * <ul>
 * <li>A janela é longa de propósito (180 dias por padrão, contra 14–30 das outras retenções): é
 * trilha de auditoria, não telemetria. Serve para reconstruir uma decisão de moderação recente —
 * contestação, revisão de caso, pedido de suporte — e não para nunca expirar.</li>
 * <li>Nada no código lê esta tabela além da escrita: nenhum painel, endpoint ou rotina consulta
 * {@code audit_logs}. A retenção não pode apagar dado que alguém está lendo.</li>
 * <li>Cada rodada apaga um lote limitado, do mais antigo para o mais novo, e o próximo ciclo
 * continua. O índice {@code (created_at)} (V261) transforma a varredura de tabela em faixa: medido
 * em MariaDB 11.4 com 400 mil linhas, de 400.001 leituras para 499.</li>
 * </ul>
 */
@Component
public class AuditLogRetentionWorker {

	private static final Logger LOG = LoggerFactory.getLogger(AuditLogRetentionWorker.class);

	private final SpringDataAuditLogJpaRepository logs;
	private final Clock clock;
	private final boolean enabled;
	private final Duration retention;
	private final int batchSize;

	public AuditLogRetentionWorker(SpringDataAuditLogJpaRepository logs, Clock clock,
			@Value("${rede.audit.retention.enabled:true}") boolean enabled,
			@Value("${rede.audit.retention.days:180}") long retentionDays,
			@Value("${rede.audit.retention.batch-size:500}") int batchSize) {
		this.logs = logs;
		this.clock = clock;
		this.enabled = enabled;
		this.retention = Duration.ofDays(retentionDays);
		this.batchSize = batchSize;
	}

	@Scheduled(initialDelayString = "${rede.audit.retention.initial-delay-ms:600000}",
			fixedDelayString = "${rede.audit.retention.interval-ms:3600000}")
	public void run() {
		if (!enabled) {
			return;
		}
		try {
			int deleted = purge();
			if (deleted > 0) {
				LOG.info("Registros de auditoria fora da janela de retencao removidos: {}", deleted);
			}
		}
		catch (RuntimeException failure) {
			LOG.warn("Falha ao remover registros de auditoria antigos: {}", failure.getMessage());
		}
	}

	/** Remove um lote de linhas fora da janela. Devolve quantas saíram (log e teste). */
	@Transactional
	public int purge() {
		List<String> expired = logs.findIdsByCreatedAtBefore(clock.instant().minus(retention),
			PageRequest.ofSize(batchSize));
		if (expired.isEmpty()) {
			return 0;
		}
		logs.deleteByIdIn(expired);
		return expired.size();
	}

	/** Quantas linhas estão elegíveis agora, sem apagar nada (diagnóstico e teste). */
	public long expiredCount() {
		return logs.countByCreatedAtBefore(clock.instant().minus(retention));
	}
}
