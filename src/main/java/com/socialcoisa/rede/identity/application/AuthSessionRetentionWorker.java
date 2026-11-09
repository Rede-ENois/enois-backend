package com.socialcoisa.rede.identity.application;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;

/**
 * Descarta as sessões que já não autenticam ninguém.
 *
 * <p>A tabela recebe uma linha por login — com o IP de onde entrou — e nenhuma rotina removia
 * nada: logout e "sair de todos" só preenchem {@code revoked_at}, e a linha continua lá para
 * sempre. Uma sessão vencida não autentica mais (nem access, nem refresh), e os usos que olham a
 * tabela precisam de pouco: o painel de contagem de ativos olha sessões dentro da janela, e a
 * moderação olha os últimos IPs de uma conta — nunca o arquivo de todos os logins da história.</p>
 *
 * <p>O corte é pela validade do refresh (30 dias; sem refresh, a do access) mais uma janela de
 * retenção, para nenhuma sessão que ainda possa renovar ser tocada. Cada rodada apaga um lote
 * limitado e o próximo ciclo continua — mesmo formato das retenções da outbox, das chaves de
 * idempotência e do log de e-mails.</p>
 */
@Component
public class AuthSessionRetentionWorker {

	private static final Logger LOG = LoggerFactory.getLogger(AuthSessionRetentionWorker.class);

	private final AuthSessionRepository sessions;
	private final Clock clock;
	private final boolean enabled;
	private final Duration retention;
	private final int batchSize;

	public AuthSessionRetentionWorker(AuthSessionRepository sessions, Clock clock,
			@Value("${rede.auth.session-retention.enabled:true}") boolean enabled,
			@Value("${rede.auth.session-retention.days:14}") long retentionDays,
			@Value("${rede.auth.session-retention.batch-size:500}") int batchSize) {
		this.sessions = sessions;
		this.clock = clock;
		this.enabled = enabled;
		this.retention = Duration.ofDays(retentionDays);
		this.batchSize = batchSize;
	}

	@Scheduled(initialDelayString = "${rede.auth.session-retention.initial-delay-ms:600000}",
			fixedDelayString = "${rede.auth.session-retention.interval-ms:3600000}")
	public void run() {
		if (!enabled) {
			return;
		}
		try {
			int deleted = purge();
			if (deleted > 0) {
				LOG.info("Sessoes vencidas fora da janela de retencao removidas: {}", deleted);
			}
		}
		catch (RuntimeException failure) {
			LOG.warn("Falha ao remover sessoes vencidas: {}", failure.getMessage());
		}
	}

	/** Remove um lote de sessões vencidas além da janela. Devolve quantas saíram (log e teste). */
	@Transactional
	public int purge() {
		return sessions.deleteExpiredBefore(clock.instant().minus(retention), batchSize);
	}

	/** Quantas sessões estão elegíveis agora, sem apagar nada (diagnóstico e teste). */
	public long expiredCount() {
		return sessions.countExpiredBefore(clock.instant().minus(retention));
	}
}
