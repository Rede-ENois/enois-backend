package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.ReportStatus;
import com.socialcoisa.rede.moderation.domain.TargetType;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class ModerationService {

	private final ReportRepository reportRepository;
	private final ModerationRemovePostUseCase moderationRemovePostUseCase;
	private final Clock clock;

	public ModerationService(ReportRepository reportRepository,
			ModerationRemovePostUseCase moderationRemovePostUseCase, Clock clock) {
		this.reportRepository = reportRepository;
		this.moderationRemovePostUseCase = moderationRemovePostUseCase;
		this.clock = clock;
	}

	@Transactional
	public Report report(String reporterId, TargetType targetType, String targetId, ReportReason reason,
			String description) {
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
		return reportRepository.save(report);
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
		return reportRepository.save(report);
	}

	@Transactional
	public Report dismiss(String reportId, String reviewerId, String note) {
		Report report = reportRepository.findById(reportId)
			.orElseThrow(() -> new ApiException(404, "REPORT_NOT_FOUND", "Denuncia nao encontrada."));
		if (report.status() != ReportStatus.PENDING) {
			throw new ApiException(409, "ALREADY_REVIEWED", "Denuncia ja foi revisada.");
		}
		report.dismiss(reviewerId, note);
		return reportRepository.save(report);
	}

	@Transactional(readOnly = true)
	public List<Report> listPending(int limit) {
		return reportRepository.findByStatus(ReportStatus.PENDING, limit);
	}
}
