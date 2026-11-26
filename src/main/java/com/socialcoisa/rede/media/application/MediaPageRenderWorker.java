package com.socialcoisa.rede.media.application;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rede de segurança do render de páginas de PDF: recolhe o que a subida não conseguiu concluir.
 *
 * <p>O caminho normal renderiza na subida, mas não todos: pod reiniciou no meio, storage estava fora,
 * fila cheia. Os pedidos continuam gravados como PENDING, e esta varredura refaz só eles — não
 * inventa trabalho para PDF que nunca foi pedido.</p>
 *
 * <p>A carência evita brigar com um render que ainda está rodando em outra thread: só é recolhido o
 * que está parado há mais que isso.</p>
 */
@Component
public class MediaPageRenderWorker {

	private static final Logger LOG = LoggerFactory.getLogger(MediaPageRenderWorker.class);

	private final MediaPageRenderService render;
	private final Clock clock;
	private final int batch;
	private final Duration grace;

	public MediaPageRenderWorker(MediaPageRenderService render, Clock clock,
			@Value("${rede.media.pages.sweep-batch:10}") int batch,
			@Value("${rede.media.pages.sweep-grace-minutes:5}") int graceMinutes) {
		this.render = render;
		this.clock = clock;
		this.batch = Math.max(1, batch);
		this.grace = Duration.ofMinutes(Math.max(1, graceMinutes));
	}

	@Scheduled(initialDelayString = "${rede.media.pages.initial-delay-ms:900000}",
			fixedDelayString = "${rede.media.pages.interval-ms:600000}")
	public void run() {
		try {
			int handled = render.sweep(batch, grace);
			if (handled > 0) {
				LOG.info("media_pages_sweep done handled={}", handled);
			}
		}
		catch (RuntimeException failure) {
			LOG.error("media_pages_sweep_failed", failure);
		}
	}
}
