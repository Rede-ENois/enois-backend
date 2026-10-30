package com.socialcoisa.rede.jobs.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Atualiza as vagas externas todo dia a meia-noite (padrao), em vez de a cada requisicao. Falha na
 * atualizacao nao derruba o app nem as vagas internas.
 */
@Component
public class ExternalJobRefreshScheduler {

	private static final Logger LOG = LoggerFactory.getLogger(ExternalJobRefreshScheduler.class);

	private final ExternalJobImportService importService;
	private final com.socialcoisa.rede.jobs.application.port.out.ExternalJobRepository repository;
	private final boolean enabled;

	public ExternalJobRefreshScheduler(ExternalJobImportService importService,
			com.socialcoisa.rede.jobs.application.port.out.ExternalJobRepository repository,
			@Value("${rede.jobs.import.external-refresh-enabled:true}") boolean enabled) {
		this.importService = importService;
		this.repository = repository;
		this.enabled = enabled;
	}

	/** Primeira carga: se ainda nao houver vaga externa salva, importa no boot (sem esperar a meia-noite). */
	@EventListener(ApplicationReadyEvent.class)
	public void seedIfEmpty() {
		if (!enabled) {
			return;
		}
		// Em SEGUNDO PLANO: a primeira carga traz TODAS as vagas da fonte (milhares) e nao pode
		// atrasar o readiness do pod. O refresh diario cuida das atualizacoes.
		//
		// Thread propria para a carga nao depender do agendador comum; inicio e fim vao para o log
		// para nao repetir o caso em que a carga simplesmente nao aconteceu e nao deixou rastro.
		LOG.info("Vagas externas: carga de boot iniciando em segundo plano.");
		Thread.ofVirtual().name("vagas-externas-boot").start(this::seedOnBoot);
	}

	private void seedOnBoot() {
		try {
			// Carrega/atualiza TODAS as vagas da fonte (idempotente: nao duplica). Roda sempre no
			// boot, em segundo plano, para o catalogo ficar completo sem esperar a meia-noite.
			LOG.info("Carga de vagas externas: {}", importService.refresh());
		}
		catch (Throwable failure) {
			LOG.warn("Falha na primeira carga de vagas externas (segue sem elas).", failure);
		}
	}

	/** Cron padrao: 00:00 todos os dias (fuso do servidor). */
	@Scheduled(cron = "${rede.jobs.import.external-refresh-cron:0 0 0 * * *}")
	public void refreshNightly() {
		if (!enabled) {
			return;
		}
		try {
			int touched = importService.refresh();
			LOG.info("Vagas externas atualizadas: {}", touched);
		}
		catch (RuntimeException failure) {
			LOG.warn("Falha ao atualizar vagas externas (segue com o que ja esta no banco).");
		}
	}
}
