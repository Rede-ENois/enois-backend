package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentAuthorReader;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentReader;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.ReportStatus;
import com.socialcoisa.rede.moderation.domain.TargetType;
import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class ModerationService {

	/** Denunciante das automações: não é conta, é o próprio sistema. */
	static final String SYSTEM_REPORTER = "system";

	private final ReportRepository reportRepository;
	private final ModerationRemovePostUseCase moderationRemovePostUseCase;
	private final ContentQuarantineReviewService quarantineReview;
	private final EmailService emailService;
	private final String supportEmail;
	private final String frontendUrl;
	private final Clock clock;
	private final com.socialcoisa.rede.profile.application.port.out.UserProfileRepository profiles;
	private final ReportedContentAuthorReader contentAuthors;
	private final ReportedContentReader contentReader;
	private final SanctionService sanctionService;
	private final NotificationService notifications;
	private final com.socialcoisa.rede.shared.security.ActionThrottle actionThrottle;
	private final ModerationLearningService learning;
	private final int reportsPerHour;

	public ModerationService(ReportRepository reportRepository,
			ModerationRemovePostUseCase moderationRemovePostUseCase,
			ContentQuarantineReviewService quarantineReview,
			EmailService emailService,
			@org.springframework.beans.factory.annotation.Value("${rede.support.email:}") String supportEmail,
			@org.springframework.beans.factory.annotation.Value("${rede.frontend.url:https://enois.ia.br}") String frontendUrl,
			com.socialcoisa.rede.profile.application.port.out.UserProfileRepository profiles,
			ReportedContentAuthorReader contentAuthors, ReportedContentReader contentReader,
			SanctionService sanctionService,
			NotificationService notifications,
			com.socialcoisa.rede.shared.security.ActionThrottle actionThrottle,
			ModerationLearningService learning,
			@org.springframework.beans.factory.annotation.Value("${rede.moderation.reports-per-hour:10}") int reportsPerHour,
			Clock clock) {
		this.reportRepository = reportRepository;
		this.moderationRemovePostUseCase = moderationRemovePostUseCase;
		this.quarantineReview = quarantineReview;
		this.emailService = emailService;
		this.supportEmail = supportEmail;
		this.frontendUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
		this.clock = clock;
		this.profiles = profiles;
		this.contentAuthors = contentAuthors;
		this.contentReader = contentReader;
		this.sanctionService = sanctionService;
		this.notifications = notifications;
		this.actionThrottle = actionThrottle;
		this.learning = learning;
		this.reportsPerHour = reportsPerHour;
	}

	/** Aviso ao suporte: best-effort. A denuncia JA ESTA salva; falha de e-mail nao perde nada. */
	private void notifySupport(Report report) {
		if (!org.springframework.util.StringUtils.hasText(supportEmail)) {
			return;
		}
		try {
			String reporter = EmailService.escapeHtml(describeUser(report.reporterId()));
			String authorId = contentAuthors.authorOf(report.targetType().name(), report.targetId()).orElse(null);
			String reported = EmailService.escapeHtml(
				authorId == null ? "(autor nao identificado para este alvo)" : describeUser(authorId));
			String content = contentReader.contentOf(report.targetType().name(), report.targetId()).orElse(null);

			StringBuilder body = new StringBuilder();
			body.append("<h2 style=\"margin:0 0 12px\">Denúncia recebida</h2>");
			body.append("<ul>")
				.append("<li><b>Denunciante:</b> ").append(reporter).append("</li>")
				.append("<li><b>Denunciado:</b> ").append(reported).append("</li>")
				.append("<li><b>Tipo da denúncia:</b> ").append(report.reason().name())
				.append(" (alvo: ").append(report.targetType().name()).append(")</li>")
				.append("<li><b>ID da denúncia:</b> ").append(report.id()).append("</li>")
				.append("</ul>");
			if (content != null) {
				body.append("<p><b>Conteúdo do post denunciado:</b></p>")
					.append("<blockquote style=\"margin:8px 0;padding:8px 12px;border-left:3px solid #ccc;")
					.append("white-space:pre-wrap\">").append(EmailService.escapeHtml(truncate(content, 2000))).append("</blockquote>");
			}
			if (report.description() != null && !report.description().isBlank()) {
				body.append("<p><b>Relato de quem denunciou:</b> ").append(EmailService.escapeHtml(report.description())).append("</p>");
			}
			String base = frontendUrl + "/moderacao/" + report.id();
			body.append("<p style=\"margin-top:16px\"><b>Ações rápidas</b> (abrem o painel; a ação é "
				+ "confirmada lá, com a sua sessão de admin):</p><ul>")
				.append("<li><a href=\"").append(base).append("?acao=penalizar\">Penalizar o autor</a></li>")
				.append("<li><a href=\"").append(base).append("?acao=avisar\">Avisar o autor</a></li>")
				.append("<li><a href=\"").append(base).append("?acao=excluir\">Excluir o post</a></li>")
				.append("<li><a href=\"").append(base).append("?acao=banir\">Banir o autor</a></li>")
				.append("</ul>");

			emailService.sendHtmlEmail(supportEmail,
				"[Denúncia " + report.id() + "] " + report.reason().name() + " em " + report.targetType().name(),
				body.toString());
		}
		catch (RuntimeException failure) {
			org.slf4j.LoggerFactory.getLogger(ModerationService.class)
				.warn("Falha ao avisar o suporte sobre a denuncia {} (ela segue registrada).", report.id());
		}
	}

	@Transactional
	public Report report(String reporterId, TargetType targetType, String targetId, ReportReason reason,
			String description) {
		// Teto por conta: denúncia em massa (coordenação) deixa de ser barata. O limite é de
		// FREQUÊNCIA, não de conteúdo — quem denuncia de boa-fé não chega perto dele. A automação
		// não passa por aqui: ela usa systemReport, onde o teto não faz sentido.
		actionThrottle.exigePermitido("report", reporterId, reportsPerHour, java.time.Duration.ofHours(1));
		if (targetType == null) {
			throw new ApiException(400, "INVALID_TARGET_TYPE", "Tipo do alvo da denuncia e obrigatorio.");
		}
		if (!org.springframework.util.StringUtils.hasText(targetId)) {
			throw new ApiException(400, "INVALID_TARGET_ID", "Alvo da denuncia e obrigatorio.");
		}
		if (reason == null) {
			throw new ApiException(400, "INVALID_REASON", "Motivo da denuncia e obrigatorio.");
		}
		if (reportRepository.existsByReporterAndTarget(reporterId, targetType.name(), targetId)) {
			throw new ApiException(409, "ALREADY_REPORTED", "Voce ja denunciou este conteudo.");
		}
		String id = "report_" + UUID.randomUUID().toString().replace("-", "");
		Report report = Report.create(id, reporterId, targetType, targetId, reason,
			description != null ? description : "", Instant.now(clock));
		Report saved = reportRepository.save(report);
		notifySupport(saved);
		return saved;
	}

	/** Denuncias do proprio usuario (mais recentes primeiro). Nao expõe dados de terceiros. */
	@Transactional(readOnly = true)
	public List<Report> reportsOf(String reporterId, int limit) {
		return reportRepository.findByReporter(reporterId, limit);
	}

	/**
	 * Denúncia criada pelo PRÓPRIO SISTEMA (automações de barreira). O teto de frequência por conta
	 * não existe aqui: quem denuncia não é uma conta, e o teto existe para conter pessoas, não a
	 * defesa da rede. O dedupe por alvo continua — um alvo, uma fila.
	 */
	@Transactional
	public Report systemReport(TargetType targetType, String targetId, ReportReason reason, String description) {
		if (targetType == null) {
			throw new ApiException(400, "INVALID_TARGET_TYPE", "Tipo do alvo da denuncia e obrigatorio.");
		}
		if (!org.springframework.util.StringUtils.hasText(targetId)) {
			throw new ApiException(400, "INVALID_TARGET_ID", "Alvo da denuncia e obrigatorio.");
		}
		if (reason == null) {
			throw new ApiException(400, "INVALID_REASON", "Motivo da denuncia e obrigatorio.");
		}
		if (reportRepository.existsByReporterAndTarget(SYSTEM_REPORTER, targetType.name(), targetId)) {
			// Já existe fila para este alvo: a automação não abre uma segunda.
			return null;
		}
		String id = "report_" + UUID.randomUUID().toString().replace("-", "");
		Report report = Report.create(id, SYSTEM_REPORTER, targetType, targetId, reason,
			description != null ? description : "", Instant.now(clock));
		Report saved = reportRepository.save(report);
		notifySupport(saved);
		return saved;
	}

	private String describeUser(String userId) {
		return profiles.findById(userId)
			.map(profile -> profile.displayWithNickname() + " (@" + profile.username() + ", id " + userId + ")")
			.orElse(userId);
	}


	private String truncate(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max) + "… [cortado]";
	}

	/** Penalizar o autor da denuncia (pessoa revisora): remove post e restringe a conta. */
	/** Retrato completo da denuncia para o painel de revisão (quem, quem, o quê). */
	@Transactional(readOnly = true)
	public ReportReview review(String reportId) {
		Report report = reportRepository.findById(reportId)
			.orElseThrow(() -> new ApiException(404, "REPORT_NOT_FOUND", "Denuncia nao encontrada."));
		String authorId = contentAuthors.authorOf(report.targetType().name(), report.targetId()).orElse(null);
		String content = contentReader.contentOf(report.targetType().name(), report.targetId()).orElse(null);
		return new ReportReview(report, describeUser(report.reporterId()), authorId,
			authorId == null ? null : describeUser(authorId), content);
	}

	public record ReportReview(Report report, String reporter, String authorId, String author, String content) {
	}

	@Transactional
	public Report penalize(String reportId, String reviewerId) {
		Report report = pendingReport(reportId);
		boolean applied = sanctionService.applyForReport(report,
			"Penalização manual por uma pessoa do time (motivo da denúncia: " + report.reason().name() + ")");
		if (!applied) {
			throw new ApiException(409, "CANNOT_PENALIZE", "Esta denúncia não pode mais ser penalizada.");
		}
		Report saved = reportRepository.findById(reportId).orElse(report);
		learning.record(saved, "PUNISH", "HUMAN", reviewerId);
		return saved;
	}

	/** Avisar o autor: a denuncia vira revisada e o autor recebe um aviso; o conteudo FICA. */
	@Transactional
	public Report warn(String reportId, String reviewerId) {
		Report report = pendingReport(reportId);
		report.approve(reviewerId, "AVISO da moderação (conteúdo mantido)");
		Report saved = reportRepository.save(report);
		// Conteúdo mantido = o que estava em quarentena por precaução volta ao ar e sai da fila.
		if (report.targetType() == TargetType.POST) {
			quarantineReview.releaseByReport(reviewerId, report.targetId());
		}
		learning.record(saved, "WARN", "HUMAN", reviewerId);
		contentAuthors.authorOf(report.targetType().name(), report.targetId()).ifPresent(authorId ->
			notifications.create(authorId, "MODERACAO", reviewerId, "Aviso da moderação",
				"Uma denúncia sobre seu conteúdo foi revisada e o time decidiu avisar você: "
					+ "revise as diretrizes da rede para não perder o acesso. O conteúdo foi mantido.",
				"/api/diretrizes", "Ver diretrizes"));
		return saved;
	}

	/** Banir o autor: remove o post (quando for post), bane a conta e revoga as sessões. */
	@Transactional
	public Report ban(String reportId, String reviewerId,
			com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository sessions) {
		Report report = pendingReport(reportId);
		String authorId = contentAuthors.authorOf(report.targetType().name(), report.targetId())
			.orElseThrow(() -> new ApiException(409, "NO_AUTHOR", "Este alvo não tem autor identificável."));
		if ("POST".equals(report.targetType().name())) {
			moderationRemovePostUseCase.removeAsModerator(reviewerId, report.targetId(), "BAN:" + report.reason().name());
		}
		report.approve(reviewerId, "BANIMENTO da conta");
		Report saved = reportRepository.save(report);
		String id = "sanc_" + java.util.UUID.randomUUID().toString().replace("-", "");
		var sanction = com.socialcoisa.rede.moderation.domain.UserSanction
			.suspended(id, authorId, report.id(), "Banimento por decisão humana (denúncia " + report.id() + ")",
				Instant.now(clock));
		sanctions_save_banned(sanction, authorId, reviewerId, sessions);
		learning.record(saved, "BAN", "HUMAN", reviewerId);
		return saved;
	}

	private void sanctions_save_banned(com.socialcoisa.rede.moderation.domain.UserSanction sanction,
			String authorId, String reviewerId,
			com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository sessions) {
		var banned = sanctionService.banAuthor(sanction, authorId, reviewerId, sessions);
		notifications.create(authorId, "MODERACAO", reviewerId, "Conta banida",
			"Uma denúncia sobre seu conteúdo foi revisada por uma pessoa do time e a conta foi banida.",
			"/api/penalidades", "Ver");
	}

	private Report pendingReport(String reportId) {
		Report report = reportRepository.findById(reportId)
			.orElseThrow(() -> new ApiException(404, "REPORT_NOT_FOUND", "Denuncia nao encontrada."));
		if (report.status() != ReportStatus.PENDING) {
			throw new ApiException(409, "ALREADY_REVIEWED", "Denuncia ja foi revisada.");
		}
		return report;
	}

	@Transactional
	public Report approve(String reportId, String reviewerId, String note) {
		Report report = reportRepository.findById(reportId)
			.orElseThrow(() -> new ApiException(404, "REPORT_NOT_FOUND", "Denuncia nao encontrada."));
		if (report.status() != ReportStatus.PENDING) {
			throw new ApiException(409, "ALREADY_REVIEWED", "Denuncia ja foi revisada.");
		}
		report.approve(reviewerId, note);
		if (report.targetType() == TargetType.POST) {
			moderationRemovePostUseCase.removeAsModerator(reviewerId, report.targetId(),
				"REPORTED:" + report.reason().name());
		}
		Report saved = reportRepository.save(report);
		learning.record(saved, "APPROVE", "HUMAN", reviewerId);
		return saved;
	}

	@Transactional
	public Report dismiss(String reportId, String reviewerId, String note) {
		Report report = reportRepository.findById(reportId)
			.orElseThrow(() -> new ApiException(404, "REPORT_NOT_FOUND", "Denuncia nao encontrada."));
		if (report.status() != ReportStatus.PENDING) {
			throw new ApiException(409, "ALREADY_REVIEWED", "Denuncia ja foi revisada.");
		}
		report.dismiss(reviewerId, note);
		Report saved = reportRepository.save(report);
		// Revisão inocentou o conteúdo: o que estava em quarentena por precaução volta ao ar e sai
		// da fila. (Só age sobre post realmente em quarentena; nos demais casos não faz nada.)
		if (report.targetType() == TargetType.POST) {
			quarantineReview.releaseByReport(reviewerId, report.targetId());
		}
		learning.record(saved, "DISMISS", "HUMAN", reviewerId);
		return saved;
	}

	@Transactional(readOnly = true)
	public List<Report> listPending(int limit) {
		return reportRepository.findByStatus(ReportStatus.PENDING, limit);
	}
}
