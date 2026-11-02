package com.socialcoisa.rede.moderation.application;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Gancho da denúncia por conteúdo sexual: esconde o post e manda o bot avaliar.
 *
 * <p>Roda só DEPOIS do commit (denúncia que sofreu rollback não esconde nada) e em outra thread —
 * baixar imagem e chamar o modelo não pode atrasar o "Denunciar" de ninguém.</p>
 */
@Component
class SexualContentReportListener {

	private static final Logger LOG = LoggerFactory.getLogger(SexualContentReportListener.class);

	private final SexualContentReportService sexualReports;
	private final Executor executor;

	SexualContentReportListener(SexualContentReportService sexualReports,
			@Qualifier("spamAutomodExecutor") Executor executor) {
		this.sexualReports = sexualReports;
		this.executor = executor;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onSexualReport(SexualContentReportEvent event) {
		if (!sexualReports.enabled()) {
			return;
		}
		executor.execute(() -> {
			try {
				sexualReports.handle(event.postId(), event.reportDescription());
			}
			catch (RuntimeException failure) {
				// Melhor esforço: a denúncia já está na fila humana e o post continua visível
				// apenas se a quarentena também falhou — nada fica em silêncio.
				LOG.warn("sexual_report_flow_failed post={}: {}", event.postId(), failure.getMessage());
			}
		});
	}
}
