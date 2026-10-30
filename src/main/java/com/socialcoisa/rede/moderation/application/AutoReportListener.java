package com.socialcoisa.rede.moderation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.shared.security.AutoReportEvent;

/**
 * Escuta {@link AutoReportEvent} publicados pelos guards de seguranca (BehavioralTimingGuard,
 * UserRateLimiter) e transforma em denuncias automaticas na moderacao.
 *
 * <p>O reporterId e "system" — nenhum usuario real e o denunciante (V116 tirou a FK que impedia).
 * O report segue o mesmo fluxo de qualquer outro e aparece no painel de moderacao para revisao
 * humana.
 *
 * <p>REQUIRES_NEW e obrigatorio: o evento chega no MEIO da transacao da acao do usuario (ex.:
 * publicar). Se a denuncia falhasse dentro dela (duplicata, throttling), a transacao ficaria
 * rollback-only e a ACAO do usuario falharia por causa de um report best-effort.
 */
@Component
class AutoReportListener {

	private static final Logger LOG = LoggerFactory.getLogger(AutoReportListener.class);

	private final ModerationService moderationService;

	AutoReportListener(ModerationService moderationService) {
		this.moderationService = moderationService;
	}

	@EventListener
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void onAutoReport(AutoReportEvent event) {
		try {
			// systemReport, não report: o teto de frequência é por CONTA e existe para conter
			// denúncia em massa de pessoas. Passando pelo caminho de usuário, a partir da 11ª
			// denúncia automática da hora o guard perdia o registro em silêncio.
			moderationService.systemReport(event.targetType(), event.userId(), event.reason(),
				event.description());
			LOG.info("Auto-report criado para usuario {} (motivo: {})", event.userId(), event.reason());
		} catch (RuntimeException e) {
			// Duplicata (409) ou qualquer outra falha: o guard ja fez o trabalho dele,
			// o report e best-effort. Em REQUIRES_NEW, falhar aqui nao afeta a acao original.
			LOG.debug("Auto-report ignorado para {}: {}", event.userId(), e.getMessage());
		}
	}
}
