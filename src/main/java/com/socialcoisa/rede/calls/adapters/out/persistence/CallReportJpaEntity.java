package com.socialcoisa.rede.calls.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.calls.domain.CallReportContext;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "call_reports")
class CallReportJpaEntity {

	@Id
	@Column(name = "report_id")
	private String reportId;

	@Column(name = "call_id", nullable = false)
	private String callId;

	@Column(name = "reported_user_id")
	private String reportedUserId;

	@Column(name = "media_id")
	private String mediaId;

	/** Lista JSON dos ids: a sala acaba, a prova do quem-estava-dentro fica. */
	@Column(nullable = false, columnDefinition = "text")
	private String participants;

	@Column(name = "reported_at", nullable = false)
	private Instant reportedAt;

	protected CallReportJpaEntity() {
	}

	static CallReportJpaEntity from(CallReportContext context) {
		CallReportJpaEntity entity = new CallReportJpaEntity();
		entity.reportId = context.reportId();
		entity.callId = context.callId();
		entity.reportedUserId = context.reportedUserId();
		entity.mediaId = context.mediaId();
		entity.participants = context.participantsJson();
		entity.reportedAt = context.reportedAt();
		return entity;
	}

	CallReportContext toDomain() {
		return CallReportContext.of(reportId, callId, reportedUserId,
			CallReportContext.participantsOf(participants), mediaId, reportedAt);
	}
}
