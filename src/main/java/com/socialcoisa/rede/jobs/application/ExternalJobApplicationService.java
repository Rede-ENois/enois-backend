package com.socialcoisa.rede.jobs.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.corporate.application.CorporateProfileService;
import com.socialcoisa.rede.corporate.domain.CorporateProfile;
import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.jobs.application.port.out.ExternalJobApplicationRepository;
import com.socialcoisa.rede.jobs.application.port.out.ExternalJobRepository;
import com.socialcoisa.rede.jobs.application.port.out.ResumeFetcher;
import com.socialcoisa.rede.jobs.domain.ExternalJob;
import com.socialcoisa.rede.jobs.domain.ExternalJobApplication;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.security.UserRateLimiter;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Candidatura a vaga EXTERNA enviada pelo Énóis.
 *
 * <p>O anúncio externo publica uma caixa de e-mail; o `mailto:` do app abre o cliente da pessoa com
 * o texto pronto, mas não consegue anexar o currículo. Aqui o e-mail sai pelo SMTP da plataforma com
 * o arquivo de verdade anexado — e o registro guarda se o anexo foi junto, para o app nunca dizer
 * "currículo enviado" quando ele não foi.</p>
 *
 * <p>Três regras que o serviço não abre mão:</p>
 * <ul>
 *   <li><b>Confirmação explícita</b>: o envio só acontece com `confirmed = true` no corpo. A prévia
 *       ({@link #draft}) existe para a pessoa ler exatamente o que vai sair antes de mandar.</li>
 *   <li><b>Nada inventado</b>: o texto usa só o que está no perfil (nome, headline, habilidades,
 *       link público). Sem headline, a frase simplesmente não aparece.</li>
 *   <li><b>Sem mentira sobre o anexo</b>: se o download do currículo falhar, o e-mail sai sem ele e
 *       a resposta diz `resumeAttached = false`.</li>
 * </ul>
 *
 * <p>Por que a reserva da linha vem antes do envio: a chave `(external_job_id, user_id)` do banco é
 * o que impede duas candidaturas à mesma vaga, inclusive de duas abas ao mesmo tempo. A linha nasce
 * `PENDING` numa transação curta, o SMTP roda FORA de qualquer transação (segurar conexão do pool
 * durante o handshake é o defeito que o suporte já corrigiu) e o status final é gravado depois.</p>
 */
@Service
public class ExternalJobApplicationService {

	private static final Logger LOG = LoggerFactory.getLogger(ExternalJobApplicationService.class);

	/** Teto do corpo que a pessoa pode acrescentar: um e-mail de candidatura é curto. */
	private static final int MAX_MESSAGE = 2000;

	private final ExternalJobRepository externalJobs;
	private final ExternalJobApplicationRepository applications;
	private final UserProfileRepository profiles;
	private final CorporateProfileService corporateProfiles;
	private final ResumeFetcher resumeFetcher;
	private final EmailService emailService;
	private final UserRateLimiter rateLimiter;
	private final TransactionTemplate transaction;
	private final Clock clock;
	private final String frontendUrl;

	public ExternalJobApplicationService(ExternalJobRepository externalJobs,
			ExternalJobApplicationRepository applications, UserProfileRepository profiles,
			CorporateProfileService corporateProfiles, ResumeFetcher resumeFetcher, EmailService emailService,
			UserRateLimiter rateLimiter, PlatformTransactionManager transactionManager, Clock clock,
			@Value("${rede.frontend.url:https://enois.ia.br}") String frontendUrl) {
		this.externalJobs = externalJobs;
		this.applications = applications;
		this.profiles = profiles;
		this.corporateProfiles = corporateProfiles;
		this.resumeFetcher = resumeFetcher;
		this.emailService = emailService;
		this.rateLimiter = rateLimiter;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
		this.frontendUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1)
			: frontendUrl;
	}

	/**
	 * O e-mail que sairia, sem enviar nada. É o que a tela mostra para a pessoa revisar — e o que
	 * torna a confirmação uma decisão informada, não um clique no escuro.
	 */
	public Draft draft(String externalJobId, String userId) {
		ExternalJob job = requireApplicableJob(externalJobId);
		UserProfile profile = requireProfile(userId);
		CorporateProfile professional = corporateProfiles.byUser(userId).orElse(null);
		return new Draft(job.applyEmail(), subjectOf(job), bodyOf(job, profile, professional, null),
			StringUtils.hasText(profile.resumeUrl()), profile.resumeName(), job.title(), job.company(),
			job.url());
	}

	/**
	 * Envia a candidatura. Só com confirmação explícita; o resto é recusado antes de qualquer envio.
	 *
	 * @param message texto opcional que a pessoa escreveu para aquela vaga (entra antes da assinatura)
	 */
	public ExternalJobApplication apply(String externalJobId, String userId, boolean confirmed, String message) {
		if (!confirmed) {
			throw new ApiException(400, "CONFIRMATION_REQUIRED",
				"Confirme o envio da candidatura antes de continuar.");
		}
		rateLimiter.require(userId, UserRateLimiter.EXTERNAL_APPLY);
		ExternalJob job = requireApplicableJob(externalJobId);
		UserProfile profile = requireProfile(userId);
		CorporateProfile professional = corporateProfiles.byUser(userId).orElse(null);

		String extra = null;
		if (StringUtils.hasText(message)) {
			extra = message.trim();
			if (extra.length() > MAX_MESSAGE) {
				throw new ApiException(400, "MESSAGE_TOO_LONG",
					"A mensagem passa de " + MAX_MESSAGE + " caracteres.");
			}
		}
		String subject = subjectOf(job);
		String body = bodyOf(job, profile, professional, extra);
		String id = reserve(externalJobId, userId, job.applyEmail(), subject, body);

		// O anexo é o ponto onde a plataforma poderia mentir: só é "anexado" se o arquivo veio.
		Optional<ResumeFetcher.FetchedResume> resume = StringUtils.hasText(profile.resumeUrl())
			? resumeFetcher.fetch(profile.resumeUrl()) : Optional.empty();
		boolean attached = resume.isPresent();
		String resumeName = attached ? resume.get().fileName() : profile.resumeName();

		// Fora de qualquer transação: o handshake do SMTP não segura conexão do pool.
		boolean sent = emailService.sendHtmlWithAttachment(job.applyEmail(), subject, body,
			"Énóis — candidatura", profile.email(),
			attached ? resume.get().bytes() : null, resumeName,
			attached ? resume.get().contentType() : null, "JOB_APPLICATION");

		ExternalJobApplication record = sent
			? ExternalJobApplication.sent(id, externalJobId, userId, job.applyEmail(), subject, body, attached,
				resumeName, clock.instant())
			: ExternalJobApplication.failed(id, externalJobId, userId, job.applyEmail(), subject, body, attached,
				resumeName, "O servidor de e-mail não aceitou a mensagem.", clock.instant());
		transaction.executeWithoutResult(status -> applications.save(record));

		if (!sent) {
			// O destinatário e o assunto NÃO vão para o log; o e-mail dele já está no registro.
			LOG.warn("Candidatura externa {} não saiu (vaga {}): o servidor de e-mail recusou.",
				record.id(), job.id());
			throw new ApiException(502, "APPLICATION_EMAIL_FAILED",
				"Não foi possível enviar o e-mail agora. Tente de novo em alguns minutos.");
		}
		return record;
	}

	/** As candidaturas externas da pessoa (a tela "minhas candidaturas" mostra as duas origens). */
	public List<ExternalJobApplication> mine(String userId, int limit) {
		return applications.findByUser(userId, Math.min(Math.max(limit, 1), 100));
	}

	/**
	 * Reserva a candidatura antes do envio.
	 *
	 * Candidatura já ENVIADA para esta vaga barra aqui (409). Uma tentativa anterior que falhou é
	 * reaproveitada — a pessoa tenta de novo e a linha é a mesma. A chave única cobre a corrida de
	 * duas requisições simultâneas: a perdedora recebe 409 em vez de disparar um segundo e-mail.
	 */
	private String reserve(String externalJobId, String userId, String recipient, String subject, String body) {
		try {
			return transaction.execute(status -> {
				ExternalJobApplication previous = applications.findByJobAndUser(externalJobId, userId).orElse(null);
				if (previous != null) {
					if (previous.delivered()) {
						throw new ApiException(409, "ALREADY_APPLIED_EXTERNAL",
							"Você já enviou uma candidatura para esta vaga pelo Énóis.");
					}
					applications.save(ExternalJobApplication.pending(previous.id(), externalJobId, userId,
						recipient, subject, body, previous.resumeName(), clock.instant()));
					return previous.id();
				}
				String id = "extapp_" + UUID.randomUUID().toString().replace("-", "");
				applications.save(ExternalJobApplication.pending(id, externalJobId, userId, recipient, subject,
					body, null, clock.instant()));
				return id;
			});
		}
		catch (DataIntegrityViolationException race) {
			throw new ApiException(409, "ALREADY_APPLIED_EXTERNAL",
				"Você já enviou uma candidatura para esta vaga pelo Énóis.");
		}
	}

	/**
	 * A vaga precisa existir, estar ativa e publicar uma caixa de e-mail. Removida ou vencida não
	 * recebe candidatura — o anúncio já não está no ar na origem.
	 */
	private ExternalJob requireApplicableJob(String externalJobId) {
		ExternalJob job = externalJobs.findById(externalJobId)
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga não encontrada."));
		if (!job.active(clock.instant())) {
			throw new ApiException(409, "JOB_NOT_ACTIVE",
				"Esta vaga não está mais ativa na origem e não recebe candidaturas.");
		}
		if (!StringUtils.hasText(job.applyEmail())) {
			throw new ApiException(409, "EXTERNAL_APPLY_UNAVAILABLE",
				"Esta vaga não publica um e-mail de candidatura. Use o link da origem.");
		}
		return job;
	}

	private UserProfile requireProfile(String userId) {
		return profiles.findById(userId)
			.orElseThrow(() -> new ApiException(404, "PROFILE_NOT_FOUND", "Perfil não encontrado."));
	}

	/**
	 * Assunto: título da vaga, empresa quando há, e o identificador da origem quando a fonte informa.
	 * É o que faz a candidatura ser encontrada na triagem de quem recebe.
	 */
	static String subjectOf(ExternalJob job) {
		StringBuilder subject = new StringBuilder("Candidatura — ").append(job.title());
		if (StringUtils.hasText(job.company())) {
			subject.append(" (").append(job.company()).append(")");
		}
		if (StringUtils.hasText(job.externalId())) {
			subject.append(" [").append(job.externalId()).append("]");
		}
		return subject.length() > 500 ? subject.substring(0, 500) : subject.toString();
	}

	/**
	 * Corpo: apresentação objetiva, o que a pessoa sabe fazer, o link do perfil público e a
	 * assinatura. Nada de "venho por meio desta" — quem lê está triando currículo.
	 */
	String bodyOf(ExternalJob job, UserProfile profile, CorporateProfile professional, String extraMessage) {
		String name = professional != null && StringUtils.hasText(professional.name())
			? professional.name() : profile.displayName();
		String headline = professional != null && StringUtils.hasText(professional.headline())
			? professional.headline() : profile.headline();
		String username = professional != null && StringUtils.hasText(professional.username())
			? professional.username() : profile.username();
		List<String> skills = profile.skills() == null ? List.of()
			: profile.skills().stream().filter(StringUtils::hasText).limit(8).toList();

		StringBuilder html = new StringBuilder();
		html.append("<div style=\"font-family:Inter,Arial,sans-serif;font-size:14px;line-height:1.6;color:#1f2937\">");
		html.append("<p>Olá!</p>\n<p>Me chamo ").append(EmailService.escapeHtml(name))
			.append(" e tenho interesse na vaga de ").append(EmailService.escapeHtml(job.title()));
		if (StringUtils.hasText(job.company())) {
			html.append(", na ").append(EmailService.escapeHtml(job.company()));
		}
		html.append(".");
		if (StringUtils.hasText(headline)) {
			html.append(" ").append(EmailService.escapeHtml(headline))
				.append(" — e acredito que posso contribuir com o time.");
		} else {
			html.append(" Acredito que posso contribuir com o time.");
		}
		html.append("</p>\n");

		if (!skills.isEmpty()) {
			html.append("<p>Algumas das minhas habilidades: ")
				.append(EmailService.escapeHtml(String.join(", ", skills))).append(".</p>\n");
		}
		if (StringUtils.hasText(username)) {
			String publicProfile = frontendUrl + "/profissional/" + username;
			html.append("<p>Meu perfil profissional: <a href=\"")
				.append(EmailService.escapeHtml(publicProfile)).append("\">")
				.append(EmailService.escapeHtml(publicProfile)).append("</a></p>\n");
		}
		if (StringUtils.hasText(extraMessage)) {
			html.append("<p>").append(EmailService.escapeHtml(extraMessage)).append("</p>\n");
		}
		html.append("<p>Fico à disposição para conversar quando fizer sentido para vocês.</p>\n");
		html.append("<p>Abraço,<br>").append(EmailService.escapeHtml(name));
		if (StringUtils.hasText(profile.portfolio())) {
			html.append("<br>").append(EmailService.escapeHtml(profile.portfolio()));
		}
		html.append("</p>\n");
		html.append("<p style=\"color:#6b7280;font-size:12px\">Enviado pelo Énóis a partir do perfil de ")
			.append(EmailService.escapeHtml(name)).append(".</p>");
		html.append("</div>");
		return html.toString();
	}

	/** O e-mail pronto para revisão: destinatário, assunto, corpo e se há currículo para anexar. */
	public record Draft(String recipientEmail, String subject, String body, boolean resumeAvailable,
			String resumeName, String jobTitle, String company, String jobUrl) {
	}
}