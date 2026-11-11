package com.socialcoisa.rede.jobs.adapters.out.notify;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.jobs.application.port.out.JobInterviewNotifier;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobInterview;
import com.socialcoisa.rede.messaging.application.MessagingService;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;

/**
 * Avisa o candidato que a entrevista foi marcada, reagendada ou cancelada: notificação in-app,
 * mensagem direta e e-mail.
 *
 * <p>Falha de entrega não derruba a entrevista (ela já foi persistida e já está na agenda): o erro
 * é logado e o candidato continua vendo o compromisso na agenda dele.
 *
 * <p>O horário vai no fuso de quem marcou, que é o mesmo da entrada da agenda — o candidato lê a
 * mesma hora nos dois lugares.
 */
@Component
class JobInterviewNotifierAdapter implements JobInterviewNotifier {

	private static final Logger LOG = LoggerFactory.getLogger(JobInterviewNotifierAdapter.class);

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm", Locale.ROOT);

	private final UserProfileRepository userProfileRepository;
	private final MessagingService messagingService;
	private final EmailService emailService;
	private final NotificationService notificationService;

	JobInterviewNotifierAdapter(UserProfileRepository userProfileRepository, MessagingService messagingService,
			EmailService emailService, NotificationService notificationService) {
		this.userProfileRepository = userProfileRepository;
		this.messagingService = messagingService;
		this.emailService = emailService;
		this.notificationService = notificationService;
	}

	@Override
	@Transactional
	public void notifyChange(Job job, JobInterview interview, String candidateId) {
		String when = whenOf(interview);
		String text = "📅 Entrevista para \"" + job.title() + "\" (" + job.company() + ")\n\n"
			+ "Marcada para " + when + " (" + interview.zone().getId() + ").";
		if (StringUtils.hasText(interview.notes())) {
			text += "\n\nObservações: " + interview.notes();
		}
		deliver(job, interview, candidateId, "Entrevista marcada", "Sua entrevista foi marcada: " + job.title(),
			text);
	}

	@Override
	@Transactional
	public void notifyCancelled(Job job, JobInterview interview, String candidateId) {
		String text = "📅 Entrevista cancelada para \"" + job.title() + "\" (" + job.company() + ")\n\n"
			+ "A entrevista de " + whenOf(interview) + " foi desmarcada pela empresa.";
		deliver(job, interview, candidateId, "Entrevista cancelada", "Sua entrevista foi cancelada: " + job.title(),
			text);
	}

	private void deliver(Job job, JobInterview interview, String candidateId, String title, String subject,
			String text) {
		notificationService.create(candidateId, "JOB_INTERVIEW", job.postedBy(), title, firstLine(text),
			"/vagas/" + job.id(), "Ver vaga");

		try {
			String conversationId = messagingService.directConversationBetween(candidateId, job.postedBy(), "JOB");
			messagingService.sendMessage(conversationId, job.postedBy(), text);
		}
		catch (RuntimeException exception) {
			LOG.warn("Entrevista {} mudou, mas a mensagem direta falhou: {}", interview.id(), exception.getMessage());
		}

		var candidate = userProfileRepository.findById(candidateId).orElse(null);
		if (candidate != null && StringUtils.hasText(candidate.email())) {
			emailService.sendHtml(candidate.email(), subject, emailBody(job, text));
		}
	}

	private static String whenOf(JobInterview interview) {
		return WHEN.format(interview.startsAt().atZone(ZoneId.of(interview.zone().getId())));
	}

	/** O corpo da notificação in-app é curto; o texto completo vai na mensagem e no e-mail. */
	private static String firstLine(String text) {
		int breakAt = text.indexOf('\n');
		return breakAt < 0 ? text : text.substring(0, breakAt);
	}

	private static String emailBody(Job job, String text) {
		StringBuilder body = new StringBuilder();
		body.append("<p><strong>").append(EmailService.escapeHtml(job.title())).append("</strong> — ")
			.append(EmailService.escapeHtml(job.company())).append("</p>");
		body.append("<p>").append(EmailService.escapeHtml(text).replace("\n", "<br/>")).append("</p>");
		body.append("<p style=\"color:#6b7280;font-size:12px\">").append("Voce recebeu este aviso porque se ")
			.append("candidatou a esta vaga no Enois.</p>");
		return body.toString();
	}
}
