package com.socialcoisa.rede.support.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.identity.application.EmailService;

/**
 * Aviso por e-mail do novo pedido de suporte, enviado DEPOIS do commit.
 *
 * <p>O envio e SMTP sincrono ({@code mailSender.send}). Antes ele acontecia dentro da transacao do
 * pedido, segurando uma conexao do Hikari durante o handshake com o servidor de e-mail — com o SMTP
 * lento ou fora do ar, cada abertura de pedido prendia uma conexao por segundos. O GitHub ja seguia
 * o padrao certo ({@link SupportGithubSync}); aqui o e-mail passa a seguir o mesmo.</p>
 *
 * <p>Falha no envio NAO derruba o pedido: ele ja esta gravado e visivel em /admin/support, e o
 * listener registra a falha. Mesma regra do servico antes da mudanca.</p>
 */
@Component
class SupportEmailNotifier {

	private static final Logger LOG = LoggerFactory.getLogger(SupportEmailNotifier.class);

	private final EmailService emailService;
	private final String supportEmail;

	SupportEmailNotifier(EmailService emailService, @Value("${rede.support.email:}") String supportEmail) {
		this.emailService = emailService;
		this.supportEmail = supportEmail;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void onReplied(SupportReplyEvent event) {
		if (!StringUtils.hasText(event.contactEmail())) {
			return;
		}
		try {
			emailService.sendSupportReplyEmail(event.contactEmail(), event.subject(), event.body());
		}
		catch (RuntimeException failure) {
			LOG.warn("Falha ao enviar a resposta do pedido {} por e-mail: {}", event.requestId(),
				failure.getClass().getSimpleName());
		}
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void onOpened(SupportRequestOpenedEvent event) {
		System.out.println("DIAG notifier onOpened supportEmail=[" + supportEmail + "]");
		if (!StringUtils.hasText(supportEmail)) {
			return;
		}
		try {
			emailService.sendSupportRequestEmail(supportEmail, event.subject(), event.message(), event.contactEmail());
		}
		catch (RuntimeException failure) {
			LOG.warn("Falha ao avisar o suporte sobre o pedido {}: {}", event.requestId(),
				failure.getClass().getSimpleName());
		}
	}
}
