package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.jobs.domain.JobInterview;
import com.socialcoisa.rede.jobs.domain.JobInterviewStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "job_interviews")
class JobInterviewJpaEntity {

	@Id
	private String id;

	@Column(name = "application_id", nullable = false)
	private Long applicationId;

	@Column(name = "job_id", nullable = false)
	private String jobId;

	@Column(name = "candidate_id", nullable = false)
	private String candidateId;

	@Column(name = "employer_id", nullable = false)
	private String employerId;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false)
	private Instant endsAt;

	@Column(name = "zone_id", nullable = false, length = 64)
	private String zoneId;

	@Column(length = 1000)
	private String notes;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private JobInterviewStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	protected JobInterviewJpaEntity() {
	}

	static JobInterviewJpaEntity fromDomain(JobInterview interview) {
		JobInterviewJpaEntity e = new JobInterviewJpaEntity();
		e.id = interview.id();
		e.applicationId = Long.valueOf(interview.applicationId());
		e.jobId = interview.jobId();
		e.candidateId = interview.candidateId();
		e.employerId = interview.employerId();
		e.startsAt = interview.startsAt();
		e.endsAt = interview.endsAt();
		e.zoneId = interview.zone().getId();
		e.notes = interview.notes();
		e.status = interview.status();
		e.createdAt = interview.createdAt();
		e.updatedAt = interview.updatedAt();
		e.cancelledAt = interview.cancelledAt();
		return e;
	}

	JobInterview toDomain() {
		return JobInterview.restore(id, String.valueOf(applicationId), jobId, candidateId, employerId, startsAt,
			endsAt, java.time.ZoneId.of(zoneId), notes, status, createdAt, updatedAt, cancelledAt);
	}
}
