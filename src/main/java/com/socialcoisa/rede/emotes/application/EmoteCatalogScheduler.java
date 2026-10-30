package com.socialcoisa.rede.emotes.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monta o catalogo de figurinhas fora do caminho da requisicao: uma vez no boot e, depois, de 3 em
 * 3 horas.
 *
 * O boot serve o retrato embarcado na hora; a montagem completa (que consulta os provedores e leva
 * alguns segundos, por causa das listagens do FFZ) roda em segundo plano para nao atrasar o readiness
 * do pod. Falha aqui nao derruba nada: o catalogo anterior continua servindo.
 */
@Component
public class EmoteCatalogScheduler {

	private static final Logger LOG = LoggerFactory.getLogger(EmoteCatalogScheduler.class);

	private final EmoteCatalogService catalogService;
	private final boolean enabled;

	public EmoteCatalogScheduler(EmoteCatalogService catalogService,
			@Value("${rede.emotes.refresh-enabled:true}") boolean enabled) {
		this.catalogService = catalogService;
		this.enabled = enabled;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void warmUpOnBoot() {
		if (!enabled) {
			return;
		}
		// Em segundo plano: as listagens do FFZ levam alguns segundos cada e o readiness nao espera.
		// Thread propria para a montagem nao depender do agendador comum. Inicio e fim vao para o log:
		// em QA de 2026-10-29 o catalogo ficou no retrato embarcado do boot ate a rodada do cron das
		// 03:00 e nao sobrou linha nenhuma dizendo se a montagem comecou e onde parou.
		LOG.info("Catalogo de figurinhas: montagem no boot iniciando em segundo plano.");
		Thread.ofVirtual().name("figurinhas-boot").start(this::buildOnBoot);
	}

	private void buildOnBoot() {
		try {
			catalogService.refresh();
			LOG.info("Catalogo de figurinhas: montagem no boot concluida.");
		}
		catch (Throwable falha) {
			// Throwable, e nao RuntimeException: um erro que nao e excecao terminava a thread sem
			// deixar rastro no log, e a montagem simplesmente nao acontecia.
			LOG.warn("Falha ao montar o catalogo de figurinhas no boot (segue com o embarcado).", falha);
		}
	}

	/**
	 * Padrao: de 3 em 3 horas. Cada rodada SOMA o que cada fonte trouxe (ver `EmoteCatalogService`);
	 * fonte que falha mantem a copia anterior, entao uma rodada ruim nao tira figurinha de ninguem.
	 */
	@Scheduled(cron = "${rede.emotes.refresh-cron:0 0 */3 * * *}")
	public void refreshDaily() {
		if (!enabled) {
			return;
		}
		try {
			catalogService.refresh();
		}
		catch (RuntimeException falha) {
			LOG.warn("Falha ao montar o catalogo de figurinhas (segue com o anterior).");
		}
	}
}