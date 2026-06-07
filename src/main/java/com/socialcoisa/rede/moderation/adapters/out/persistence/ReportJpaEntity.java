package com.socialcoisa.rede.moderation.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.ReportStatus;
import com.socialcoisa.rede.moderation.domain.TargetType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "reports", indexes = {
	@Index(name = "idx_reports_status_created", columnList = "status, created_at"),
	@Index(name = "idx_reports_target", columnList = "target_type, target_id"),
	@Index(name = "idx_reports_reporter", columnList = "reporter_id") })
class ReportJpaEntity {

	@Id
	private String id;

	@Column(name = "reporter_id", nullable = false)
	private String reporterId;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", nullable = false)
	private TargetType targetType;

	@Column(name = "target_id", nullable = false)
	private String targetId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ReportReason reason;

	@Column(length = 2000)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ReportStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "reviewed_by")
	private String reviewedBy;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	@Column(name = "resolution_note", length = 1000)
	private String resolutionNote;

	protected ReportJpaEntity() {
	}

	static ReportJpaEntity fromDomain(Report report) {
		ReportJpaEntity e = new ReportJpaEntity();
		e.id = report.id();
		e.reporterId = report.reporterId();
		e.targetType = report.targetType();
		e.targetId = report.targetId();
		e.reason = report.reason();
		e.description = report.description();
		e.status = report.status();
		e.createdAt = report.createdAt();
		e.reviewedBy = report.reviewedBy();
		e.reviewedAt = report.reviewedAt();
		e.resolutionNote = report.resolutionNote();
		return e;
	}

	Report toDomain() {
		return Report.restore(id, reporterId, targetType, targetId, reason, description, status, createdAt,
			reviewedBy, reviewedAt, resolutionNote);
	}
}
