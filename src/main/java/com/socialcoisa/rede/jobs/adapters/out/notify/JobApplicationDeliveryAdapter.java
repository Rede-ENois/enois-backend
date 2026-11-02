package com.socialcoisa.rede.jobs.adapters.out.notify;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.jobs.application.port.out.JobApplicationDelivery;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobApplication;
import com.socialcoisa.rede.messaging.application.MessagingService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;

/**
 * Entrega da candidatura para quem publicou a vaga: e-mail com o perfil do candidato e uma mensagem
 * direta na conversa entre os dois.
 *
 * Falha de entrega nao derruba a candidatura (ela ja esta registrada): o erro e logado e o empregador
 * continua vendo a candidatura na lista da vaga.
 */
@Component
class JobApplicationDeliveryAdapter implements JobApplicationDelivery {

	private static final Logger LOG = LoggerFactory.getLogger(JobApplicationDeliveryAdapter.class);

	private final UserProfileRepository userProfileRepository;
	private final MessagingService messagingService;
	private final EmailService emailService;
	private final TransactionTemplate dmTransaction;

	JobApplicationDeliveryAdapter(UserProfileRepository userProfileRepository, MessagingService messagingService,
			EmailService emailService, PlatformTransactionManager transactionManager) {
		this.userProfileRepository = userProfileRepository;
		this.messagingService = messagingService;
		this.emailService = emailService;
		this.dmTransaction = new TransactionTemplate(transactionManager);
		this.dmTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Override
	@Transactional
	public void deliver(Job job, JobApplication application) {
		var employer = userProfileRepository.findById(job.postedBy()).orElse(null);
		if (employer == null) {
			return;
		}
		String summary = summaryText(job, application, candidateName(application));

		// Transacao propria: o sendMessage e @Transactional, e uma recusa dele (portao de idade,
		// sancao, rate limit) marcaria a transacao da candidatura como rollback-only mesmo com o catch
		// abaixo — o commit viraria UnexpectedRollbackException e a candidatura, 500.
		try {
			dmTransaction.executeWithoutResult(status -> {
				String conversationId = messagingService.directConversationBetween(application.userId(), job.postedBy(), "JOB");
				messagingService.sendMessage(conversationId, application.userId(), summary);
			});
		}
		catch (RuntimeException exception) {
			LOG.warn("Candidatura {} registrada, mas a mensagem direta falhou: {}", application.id(),
				exception.getMessage());
		}

		if (StringUtils.hasText(employer.email())) {
			emailService.sendHtml(employer.email(), "Nova candidatura: " + job.title(),
				emailBody(job, application, summary));
		}
	}

	/**
	 * Quem se candidatou, como a empresa deve ver: o nome e o @ do retrato (a identidade
	 * profissional desde a separacao das interfaces), nunca o @ social lido agora do perfil.
	 */
	static String candidateName(JobApplication application) {
		String name = snapshot(application, "name");
		String username = snapshot(application, "username");
		String base = StringUtils.hasText(name) ? name : "Candidato(a)";
		return StringUtils.hasText(username) ? base + " (@" + username + ")" : base;
	}

	/** Candidaturas antigas (retrato SOCIAL) continuam apontando para o perfil em que foram feitas. */
	static String profilePath(JobApplication application, String username) {
		return "CORPORATE".equals(snapshot(application, "identity")) ? "/profissional/" + username
			: "/perfil/" + username;
	}

	/** Resumo que vai na DM e no e-mail: e o que o empregador le primeiro. */
	private String summaryText(Job job, JobApplication application, String candidateName) {
		StringBuilder text = new StringBuilder();
		text.append("📄 Candidatura para \"").append(job.title()).append("\" (").append(job.company()).append(")\n\n");
		text.append("Candidato(a): ").append(candidateName);
		String headline = snapshot(application, "headline");
		if (StringUtils.hasText(headline)) {
			text.append(" — ").append(headline);
		}
		text.append("\n");
		// Currículo em anexo-por-link: é o primeiro documento que o empregador procura.
		String resumeUrl = snapshot(application, "resumeUrl");
		if (StringUtils.hasText(resumeUrl)) {
			String resumeName = snapshot(application, "resumeName");
			text.append("Currículo: ").append(StringUtils.hasText(resumeName) ? resumeName + " — " : "")
				.append(resumeUrl).append("\n");
		}
		String location = snapshot(application, "location");
		if (StringUtils.hasText(location)) {
			text.append("Local: ").append(location).append("\n");
		}
		int years = Integer.parseInt(snapshotOr(application, "experienceYears", "0"));
		int experiences = Integer.parseInt(snapshotOr(application, "experiencesCount", "0"));
		text.append("Experiência: ").append(years).append(years == 1 ? " ano" : " anos")
			.append(" (").append(experiences).append(experiences == 1 ? " experiência" : " experiências").append(")\n");
		text.append("Reputação: ").append(snapshotOr(application, "reputation", "0"))
			.append(Boolean.parseBoolean(snapshotOr(application, "identityVerified", "false"))
				? " · conta verificada" : " · conta ainda não verificada")
			.append("\n");
		String skills = snapshotList(application, "skills");
		if (StringUtils.hasText(skills)) {
			text.append("Habilidades: ").append(skills).append("\n");
		}
		String username = snapshot(application, "username");
		if (StringUtils.hasText(username)) {
			text.append("Perfil: ").append(profilePath(application, username)).append("\n");
		}
		if (StringUtils.hasText(application.message())) {
			text.append("\nMensagem do candidato:\n").append(application.message()).append("\n");
		}
		if (!application.answers().isEmpty()) {
			text.append("\nRespostas:\n");
			for (JobApplication.JobApplicationAnswer answer : application.answers()) {
				text.append("• ").append(answer.label()).append(": ").append(answer.value()).append("\n");
			}
		}
		return text.toString().trim();
	}

	private String emailBody(Job job, JobApplication application, String summary) {
		return "<div style=\"font-family:Inter,Arial,sans-serif;font-size:14px;line-height:1.6;color:#1f2937\">"
			+ "<p>Você recebeu uma candidatura para <strong>"
			+ EmailService.escapeHtml(job.title()) + "</strong>.</p>"
			+ "<pre style=\"white-space:pre-wrap;font-family:inherit;background:#f6f5ff;padding:16px;border-radius:12px\">"
			+ EmailService.escapeHtml(summary) + "</pre>"
			+ "<p>Responda pelo app, na conversa direta com o candidato.</p></div>";
	}

	private List<String> snapshotValues(JobApplication application, String field) {
		try {
			var node = new tools.jackson.databind.ObjectMapper()
				.readTree(application.candidateSnapshot() == null ? "{}" : application.candidateSnapshot())
				.path(field);
			if (!node.isArray()) {
				return List.of();
			}
			List<String> values = new java.util.ArrayList<>();
			node.forEach(item -> values.add(item.asText("")));
			return values;
		}
		catch (Exception exception) {
			return List.of();
		}
	}

	private static String snapshot(JobApplication application, String field) {
		return snapshotOr(application, field, "");
	}

	private static String snapshotOr(JobApplication application, String field, String fallback) {
		try {
			var node = new tools.jackson.databind.ObjectMapper()
				.readTree(application.candidateSnapshot() == null ? "{}" : application.candidateSnapshot())
				.path(field);
			return node.isMissingNode() || node.isNull() ? fallback : node.asText(fallback);
		}
		catch (Exception exception) {
			return fallback;
		}
	}

	private String snapshotList(JobApplication application, String field) {
		return String.join(", ", snapshotValues(application, field));
	}
}
