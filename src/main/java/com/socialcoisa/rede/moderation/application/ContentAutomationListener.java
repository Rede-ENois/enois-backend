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
 * Escuta a publicação de posts e dispara a checagem de conteúdo automatizado FORA da transação.
 *
 * <p>Mesmas duas garantias do automod de spam: só roda depois do commit (post que sofreu rollback
 * não é avaliado) e em outra thread — a análise chama o modelo e não pode atrasar o "Publicar".</p>
 */
@Component
class ContentAutomationListener {

	private static final Logger LOG = LoggerFactory.getLogger(ContentAutomationListener.class);

	private final ContentAutomationService automation;
	private final Executor executor;

	ContentAutomationListener(ContentAutomationService automation,
			@Qualifier("spamAutomodExecutor") Executor executor) {
		this.automation = automation;
		this.executor = executor;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onPostPublished(PostPublishedEvent event) {
		executor.execute(() -> {
			try {
				automation.evaluate(event.postId());
			}
			catch (RuntimeException failure) {
				// Melhor esforço: falha aqui nunca afeta a publicação (que já está no ar).
				LOG.warn("content_automation_falhou post={}: {}", event.postId(), failure.getMessage());
			}
		});
	}
}