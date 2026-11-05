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

import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;

/**
 * Varredura periódica dos links publicados.
 *
 * <p>O gancho de publicação cobre o que entra novo; esta varredura cobre o que já estava no ar
 * quando a barreira subiu e o que escapou por falha momentânea (destino fora do ar, executor cheio).
 * A janela é de horas, não de dias: link que já foi julgado está no ledger, então revisitar o mesmo
 * período é barato — o que custa é visitar destino novo.</p>
 */
@Component
public class LinkSafetySweepWorker {

	private static final Logger LOG = LoggerFactory.getLogger(LinkSafetySweepWorker.class);

	private final LinkSafetyService linkSafety;
	private final PostRepository posts;
	private final Clock clock;
	private final int batch;
	private final int lookbackHours;

	private final AtomicBoolean rodando = new AtomicBoolean(false);

	LinkSafetySweepWorker(LinkSafetyService linkSafety, PostRepository posts, Clock clock,
			@Value("${rede.moderation.links.sweep-batch:15}") int batch,
			@Value("${rede.moderation.links.lookback-hours:72}") int lookbackHours) {
		this.linkSafety = linkSafety;
		this.posts = posts;
		this.clock = clock;
		this.batch = Math.max(1, batch);
		this.lookbackHours = Math.max(1, lookbackHours);
	}

	@Scheduled(initialDelayString = "${rede.moderation.links.initial-delay-ms:60000}",
			fixedDelayString = "${rede.moderation.links.interval-ms:300000}")
	public int sweep() {
		if (!linkSafety.enabled()) {
			return 0;
		}
		// Rodada anterior ainda visitando destinos: não empilha outra.
		if (!rodando.compareAndSet(false, true)) {
			return 0;
		}
		try {
			Instant desde = clock.instant().minus(Duration.ofHours(lookbackHours));
			int checados = 0;
			for (Post post : posts.findActiveWithLinksSince(desde, batch)) {
				try {
					linkSafety.check(post.id().value());
					checados++;
				}
				catch (RuntimeException falha) {
					LOG.warn("link_sweep_post_falhou post={} motivo={}", post.id().value(),
						falha.getClass().getSimpleName());
				}
			}
			return checados;
		}
		finally {
			rodando.set(false);
		}
	}
}
