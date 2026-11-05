package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rede de segurança da checagem de mídia: pega o que escapou do gancho de publicação.
 *
 * <p>O listener cobre o caminho normal, mas não todos: post publicado enquanto o pod reiniciava,
 * mídia de comentário, mensagem, ou um lote que subiu antes desta versão existir. A varredura olha
 * as imagens recentes ainda sem checagem e passa cada uma pelo mesmo serviço — o ledger garante que
 * a mesma mídia nunca é analisada duas vezes.</p>
 *
 * <p>Janela curta de propósito: conteúdo explícito precisa sair do ar em minutos, não em dias.
 * O lote é pequeno porque cada item custa um download e uma chamada de modelo.</p>
 */
@Component
public class MediaSafetySweepWorker {

	private static final Logger LOG = LoggerFactory.getLogger(MediaSafetySweepWorker.class);

	private final MediaSafetyService mediaSafety;
	private final Clock clock;
	private final int batch;
	private final int lookbackHours;

	public MediaSafetySweepWorker(MediaSafetyService mediaSafety, Clock clock,
			@Value("${rede.moderation.media.sweep-batch:25}") int batch,
			@Value("${rede.moderation.media.lookback-hours:72}") int lookbackHours) {
		this.mediaSafety = mediaSafety;
		this.clock = clock;
		this.batch = Math.max(1, batch);
		this.lookbackHours = Math.max(1, lookbackHours);
	}

	@Scheduled(initialDelayString = "${rede.moderation.media.initial-delay-ms:45000}",
			fixedDelayString = "${rede.moderation.media.interval-ms:300000}")
	public void run() {
		if (!mediaSafety.enabled()) {
			return;
		}
		try {
			sweep();
		}
		catch (RuntimeException failure) {
			LOG.error("media_safety_sweep_failed", failure);
		}
	}

	/** Uma passada; devolve quantas mídias foram barradas (também usado pelos testes). */
	public int sweep() {
		Instant since = clock.instant().minus(Duration.ofHours(lookbackHours));
		int enforced = 0;
		int checked = 0;
		for (String mediaId : mediaSafety.pending(since, batch)) {
			MediaSafetyService.Outcome outcome = mediaSafety.check(mediaId);
			checked++;
			if ("ENFORCED".equals(outcome.status())) {
				enforced++;
			}
		}
		if (checked > 0) {
			LOG.info("media_safety_sweep done checked={} enforced={}", checked, enforced);
		}
		return enforced;
	}
}
