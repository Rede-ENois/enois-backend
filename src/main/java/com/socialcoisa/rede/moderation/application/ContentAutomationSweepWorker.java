package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rede de segurança da checagem de conteúdo automatizado: pega o que escapou do gancho de
 * publicação.
 *
 * <p>O listener cobre o caminho normal, mas não todos: post publicado enquanto o pod reiniciava, ou
 * um lote que subiu antes desta versão existir. A varredura olha os posts recentes ainda sem
 * avaliação e passa cada um pelo mesmo serviço — o livro-caixa garante que o mesmo texto nunca é
 * analisado duas vezes.</p>
 *
 * <p>A guarda de reentrância importa aqui: uma passada chama o modelo por item e pode demorar mais
 * que o intervalo entre varreduras.</p>
 */
@Component
public class ContentAutomationSweepWorker {

	private static final Logger LOG = LoggerFactory.getLogger(ContentAutomationSweepWorker.class);

	private final ContentAutomationService automation;
	private final Clock clock;
	private final int batch;
	private final int lookbackHours;
	private final AtomicBoolean rodando = new AtomicBoolean(false);

	public ContentAutomationSweepWorker(ContentAutomationService automation, Clock clock,
			@Value("${rede.moderation.automation.sweep-batch:20}") int batch,
			@Value("${rede.moderation.automation.lookback-hours:72}") int lookbackHours) {
		this.automation = automation;
		this.clock = clock;
		this.batch = Math.max(1, batch);
		this.lookbackHours = Math.max(1, lookbackHours);
	}

	@Scheduled(initialDelayString = "${rede.moderation.automation.initial-delay-ms:90000}",
			fixedDelayString = "${rede.moderation.automation.interval-ms:600000}")
	public void run() {
		if (!automation.enabled() || !rodando.compareAndSet(false, true)) {
			return;
		}
		try {
			sweep();
		}
		catch (RuntimeException failure) {
			LOG.error("content_automation_sweep_failed", failure);
		}
		finally {
			rodando.set(false);
		}
	}

	/** Uma passada; devolve quantos posts entraram em quarentena (também usado pelos testes). */
	public int sweep() {
		Instant since = clock.instant().minus(Duration.ofHours(lookbackHours));
		int quarentenados = 0;
		int avaliados = 0;
		for (String postId : automation.pending(since, batch)) {
			ContentAutomationService.Outcome outcome = automation.evaluate(postId);
			avaliados++;
			if (outcome.quarantined()) {
				quarentenados++;
			}
		}
		if (avaliados > 0) {
			LOG.info("content_automation_sweep done avaliados={} quarentenados={}", avaliados, quarentenados);
		}
		return quarentenados;
	}
}