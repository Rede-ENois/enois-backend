package com.socialcoisa.rede.moderation.application;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.socialcoisa.rede.feed.application.PostPublishedEvent;

/**
 * Checa os links de um post assim que ele entra no ar.
 *
 * <p>É o gancho que fecha o caso do texto inocente com destino proibido: a triagem de texto não vê
 * a página apontada, e esperar uma denúncia deixa o link no ar. Aqui o destino é lido poucos
 * segundos depois da publicação, e o post sai se a leitura comprovar a violação.</p>
 *
 * <p>Mesmas garantias do automod de spam: roda só DEPOIS do commit e em outra thread — ler página
 * externa não pode atrasar o "Publicar" de ninguém.</p>
 */
@Component
class LinkSafetyListener {

	private static final Logger LOG = LoggerFactory.getLogger(LinkSafetyListener.class);

	private final LinkSafetyService linkSafety;
	private final Executor executor;

	LinkSafetyListener(LinkSafetyService linkSafety, @Qualifier("spamAutomodExecutor") Executor executor) {
		this.linkSafety = linkSafety;
		this.executor = executor;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onPostPublished(PostPublishedEvent event) {
		if (!linkSafety.enabled()) {
			return;
		}
		executor.execute(() -> {
			try {
				linkSafety.check(event.postId());
			}
			catch (RuntimeException failure) {
				// Melhor esforço: o post já está no ar e a varredura periódica cobre o caso.
				LOG.warn("link_safety_falhou post={}: {}", event.postId(), failure.getMessage());
			}
		});
	}
}
