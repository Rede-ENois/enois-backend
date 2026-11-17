package com.socialcoisa.rede.feed.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.feed.application.port.out.PostRepository;

/**
 * O efêmero se apaga sozinho: a cada 5 minutos o worker procura os posts efêmeros (Momento 24h,
 * Instante 1h) ativos cujo prazo venceu e aplica o soft-delete do fluxo normal (feed, cache e
 * mídia iguais à exclusão). Granularidade de 5 min — o post some do app logo depois do prazo,
 * sem depender de ninguém.
 */
@Component
public class MomentExpiryScheduler {

	private static final int LOTE = 200;

	private final PostRepository posts;
	private final DeletePostService deletePostService;
	private final Clock clock;

	public MomentExpiryScheduler(PostRepository posts, DeletePostService deletePostService, Clock clock) {
		this.posts = posts;
		this.deletePostService = deletePostService;
		this.clock = clock;
	}

	@Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
	public void expireDueMoments() {
		Instant now = clock.instant();
		for (var post : posts.findExpiredEphemeral(now, LOTE)) {
			try {
				deletePostService.expireMoment(post.id());
			} catch (RuntimeException ignored) {
				// a próxima rodada tenta de novo; o worker não pode cair por um post
			}
		}
	}
}
