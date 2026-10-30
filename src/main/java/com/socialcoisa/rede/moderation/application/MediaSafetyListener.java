package com.socialcoisa.rede.moderation.application;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.socialcoisa.rede.feed.application.PostPublishedEvent;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.PostId;

/**
 * Checa a mídia de um post assim que ele entra no ar.
 *
 * <p>É o gancho que faltava: antes, publicar com foto explícita não disparava nada além do automod
 * de SPAM, que só olha contagem e texto repetido. Aqui a imagem é olhada de verdade, e o post sai do
 * ar poucos segundos depois de publicado.</p>
 *
 * <p>Mesmas garantias do automod de spam: roda só DEPOIS do commit (post que sofreu rollback não é
 * analisado) e em outra thread — baixar imagem e chamar o modelo não pode atrasar o "Publicar" de
 * ninguém.</p>
 */
@Component
class MediaSafetyListener {

	private static final Logger LOG = LoggerFactory.getLogger(MediaSafetyListener.class);

	private final MediaSafetyService mediaSafety;
	private final PostRepository posts;
	private final Executor executor;

	MediaSafetyListener(MediaSafetyService mediaSafety, PostRepository posts,
			@Qualifier("spamAutomodExecutor") Executor executor) {
		this.mediaSafety = mediaSafety;
		this.posts = posts;
		this.executor = executor;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onPostPublished(PostPublishedEvent event) {
		if (!mediaSafety.enabled()) {
			return;
		}
		executor.execute(() -> {
			try {
				var post = posts.findById(new PostId(event.postId())).orElse(null);
				if (post == null || post.mediaIds().isEmpty()) {
					return;
				}
				for (String mediaId : post.mediaIds()) {
					mediaSafety.check(mediaId);
				}
			}
			catch (RuntimeException failure) {
				// Melhor esforço: o post já está no ar e a varredura periódica cobre o caso.
				LOG.warn("media_safety_falhou post={}: {}", event.postId(), failure.getMessage());
			}
		});
	}
}
