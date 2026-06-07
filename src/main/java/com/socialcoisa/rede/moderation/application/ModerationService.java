package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.application.DeletePostCommand;
import com.socialcoisa.rede.feed.application.port.in.DeletePostUseCase;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.ReportStatus;
import com.socialcoisa.rede.moderation.domain.TargetType;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class ModerationService {

	private final ReportRepository reportRepository;
	private final DeletePostUseCase deletePostUseCase;
	private final Clock clock;

	public ModerationService(ReportRepository reportRepository, DeletePostUseCase deletePostUseCase, Clock clock) {
		this.reportRepository = reportRepository;
		this.deletePostUseCase = deletePostUseCase;
		this.clock = clock;
	}

	@Transactional
	public Report report(String reporterId, TargetType targetType, String targetId, ReportReason reason,
			String description) {
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
			deletePostUseCase.delete(new DeletePostCommand(new UserId(reviewerId), new PostId(report.targetId()),
				"REPORTED:" + report.reason().name()));
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
