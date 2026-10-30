package com.socialcoisa.rede.jobs.adapters.out.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.jobs.application.port.out.JobApplicationStatusNotifier;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobApplication;
import com.socialcoisa.rede.jobs.domain.JobApplicationStatus;
import com.socialcoisa.rede.messaging.application.MessagingService;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;

/**
 * Notifica o candidato sobre a mudanca de estado: notificacao in-app (que tambem vira push, se a
 * pessoa nao desligou vagas), mensagem direta e e-mail.
 *
 * Falha de entrega nao derruba a mudanca de estado (ela ja foi persistida antes daqui): o erro e
 * logado e o candidato continua vendo o estado atualizado na lista dele.
 *
 * O texto nao traz dado de outro candidato nem nota interna — so o titulo da vaga e o estado.
 */
@Component
class JobApplicationStatusNotifierAdapter implements JobApplicationStatusNotifier {

	private static final Logger LOG = LoggerFactory.getLogger(JobApplicationStatusNotifierAdapter.class);

	private final UserProfileRepository userProfileRepository;
	private final MessagingService messagingService;
	private final EmailService emailService;
	private final NotificationService notificationService;

	JobApplicationStatusNotifierAdapter(UserProfileRepository userProfileRepository, MessagingService messagingService,
			EmailService emailService, NotificationService notificationService) {
		this.userProfileRepository = userProfileRepository;
		this.messagingService = messagingService;
		this.emailService = emailService;
		this.notificationService = notificationService;
	}

	@Override
	@Transactional
	public void notifyStatusChange(Job job, JobApplication application, JobApplicationStatus status) {
		String text = messageFor(job, application, status);

		// Sem o retorno da empresa: o corpo pode aparecer na tela bloqueada (push); o retorno segue na mensagem direta, no e-mail e na lista de candidaturas.
		notificationService.create(application.userId(), "JOB_APPLICATION_STATUS", job.postedBy(),
			"Atualização da sua candidatura", statusLine(job, status), "/vagas/" + job.id(), "Ver vaga");

		try {
			String conversationId = messagingService.directConversationBetween(application.userId(), job.postedBy());
			// Sai do empregador: e ele quem decidiu, e a conversa e a mesma criada na candidatura.
			messagingService.sendMessage(conversationId, job.postedBy(), text);
		}
		catch (RuntimeException exception) {
			LOG.warn("Estado da candidatura {} mudou, mas a mensagem direta falhou: {}", application.id(),
				exception.getMessage());
		}

		var candidate = userProfileRepository.findById(application.userId()).orElse(null);
		if (candidate != null && StringUtils.hasText(candidate.email())) {
			emailService.sendHtml(candidate.email(), subjectFor(job, status), emailBody(job, status, text));
		}
	}

	private String messageFor(Job job, JobApplication application, JobApplicationStatus status) {
		String base = "📋 Atualização da sua candidatura para \"" + job.title() + "\" (" + job.company()
			+ ")\n\n" + switch (status) {
				case IN_REVIEW -> "Sua candidatura está em análise.";
				case ADVANCED -> "Você avançou para a próxima etapa do processo. Fique de olho no seu e-mail.";
				case NOT_SELECTED -> "A empresa seguiu com outras pessoas desta vez. Obrigado por participar.";
				case APPLIED -> "Sua candidatura foi recebida.";
			};
		// O retorno do empregador vem junto: e o que faz a resposta ser util para quem se candidatou.
		return application.feedback() == null ? base
			: base + "\n\nRetorno da empresa: " + application.feedback();
	}

	private String statusLine(Job job, JobApplicationStatus status) {
		String title = subjectText(job.title());
		return switch (status) {
			case IN_REVIEW -> "Sua candidatura para \"" + title + "\" está em análise.";
			case ADVANCED -> "Você avançou no processo de \"" + title + "\".";
			case NOT_SELECTED -> "A empresa respondeu sobre \"" + title + "\".";
			case APPLIED -> "Sua candidatura para \"" + title + "\" foi recebida.";
		};
	}

	private String subjectFor(Job job, JobApplicationStatus status) {
		String title = subjectText(job.title());
		return switch (status) {
			case IN_REVIEW -> "Sua candidatura está em análise: " + title;
			case ADVANCED -> "Você avançou no processo: " + title;
			case NOT_SELECTED -> "Sobre a sua candidatura: " + title;
			case APPLIED -> "Candidatura recebida: " + title;
		};
	}

	/** Assunto de e-mail nao aceita quebra de linha (viraria cabecalho) nem marcação. */
	private String subjectText(String value) {
		return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
	}

	private String emailBody(Job job, JobApplicationStatus status, String text) {
		StringBuilder body = new StringBuilder();
		// Titulo e empresa vem do anuncio do empregador: entram escapados, senao um titulo com
		// <a href> vira link de phishing dentro do e-mail que chega ao candidato.
		body.append("<p><strong>").append(EmailService.escapeHtml(job.title())).append("</strong> — ")
			.append(EmailService.escapeHtml(job.company())).append("</p>");
		body.append("<p>").append(EmailService.escapeHtml(text).replace("\n", "<br/>")).append("</p>");
		body.append("<p style=\"color:#6b7280;font-size:12px\">").append("Voce recebeu este aviso porque se ")
			.append("candidatou a esta vaga no Enois.</p>");
		return body.toString();
	}
}
