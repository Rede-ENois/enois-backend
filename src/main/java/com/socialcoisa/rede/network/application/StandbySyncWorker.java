package com.socialcoisa.rede.network.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Mantém fresca a cópia do banco nos nós de reserva, e roda a sincronização completa de madrugada
 * (V360, brief cooperativo §11/§12).
 *
 * <p>Três horários, por motivo diferente:</p>
 * <ul>
 * <li><b>Refresh de seis em seis horas</b> (o pedido de "sincronização automática durante o dia"):
 * pelo menos uma cópia completa do dia. Janela curta o bastante para o banco de reserva ter o estado
 * de hoje, longe da carga de madrugada.</li>
 * <li><b>Sincronização completa às 03:00</b> (fuso do servidor): a janela em que ninguém está
 * escrevendo, para a cópia que vale no failover. Quando não há cópia de reserva declarada, o job não
 * inventa uma: a lista de reservas é do operador.</li>
 * <li><b>Desligado por padrão</b> ({@code rede.network.standby.auto-sync.enabled=false}): o dump que
 * a cópia dispara exige o {@code mariadb-dump} e a senha do banco, e um job que consulta o banco em
 * ambiente de desenvolvimento sem nó de reserva nenhum só gastaria I/O.</li>
 * </ul>
 *
 * <p>O cron é configurável em vez de fixo no código para a produção poder alinhar com o seu fuso:
 * {@code rede.network.standby.full-sync-cron:0 0 3 * * *}.</p>
 */
@Component
class StandbySyncWorker {

	private static final Logger LOG = LoggerFactory.getLogger(StandbySyncWorker.class);

	private final StandbySyncService standby;
	private final boolean enabled;

	StandbySyncWorker(StandbySyncService standby,
			@Value("${rede.network.standby.auto-sync.enabled:false}") boolean enabled) {
		this.standby = standby;
		this.enabled = enabled;
	}

	/** O refresh curto do dia: só age quando nenhum nó tem cópia completa dentro da janela. */
	@Scheduled(initialDelayString = "${rede.network.standby.refresh-initial-delay-ms:300000}",
		fixedDelayString = "${rede.network.standby.refresh-delay-ms:21600000}")
	public void refreshDuringTheDay() {
		if (!enabled) {
			return;
		}
		try {
			int copied = standby.refreshDaily();
			if (copied > 0) {
				LOG.info("Refresh do banco do Control Plane: {} cópia(s) concluída(s)", copied);
			}
		}
		catch (RuntimeException failure) {
			// Um refresh que falha não pode derrubar o agendador: a próxima janela tenta de novo, e
			// o estado de cada cópia já está no painel com o motivo.
			LOG.warn("Refresh automático da cópia do banco falhou: {}", failure.getMessage());
		}
	}

	/** A sincronização completa da madrugada. Mesma operação da cópia hoje — ver {@link StandbySyncService#fullSync}. */
	@Scheduled(cron = "${rede.network.standby.full-sync-cron:0 0 3 * * *}")
	public void fullSyncBeforeDawn() {
		if (!enabled) {
			return;
		}
		try {
			int copied = standby.refreshDaily();
			LOG.info("Sincronização completa (03:00) da cópia do banco: {} cópia(s) concluída(s)", copied);
		}
		catch (RuntimeException failure) {
			LOG.warn("Sincronização completa da cópia do banco falhou: {}", failure.getMessage());
		}
	}
}