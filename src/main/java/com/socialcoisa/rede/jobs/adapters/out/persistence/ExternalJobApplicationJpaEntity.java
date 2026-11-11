package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.jobs.domain.ExternalJobApplication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "external_job_applications",
	uniqueConstraints = @UniqueConstraint(name = "uk_external_job_application",
		columnNames = { "external_job_id", "user_id" }),
	indexes = @Index(name = "idx_external_job_applications_user", columnList = "user_id, applied_at"))
class ExternalJobApplicationJpaEntity {

	@Id
	private String id;

	@Column(name = "external_job_id", nullable = false)
	private String externalJobId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "recipient_email", nullable = false, length = 320)
	private String recipientEmail;

	@Column(nullable = false, length = 500)
	private String subject;

	@Column(nullable = false, columnDefinition = "mediumtext")
	private String body;

	@Column(name = "resume_attached", nullable = false)
	private boolean resumeAttached;

	@Column(name = "resume_name", length = 200)
	private String resumeName;

	@Column(nullable = false, length = 20)
	private String status;

	@Column(length = 500)
	private String error;

	@Column(name = "applied_at", nullable = false)
	private Instant appliedAt;

	protected ExternalJobApplicationJpaEntity() {
	}

	static ExternalJobApplicationJpaEntity fromDomain(ExternalJobApplication application) {
		ExternalJobApplicationJpaEntity e = new ExternalJobApplicationJpaEntity();
		e.id = application.id();
		e.externalJobId = application.externalJobId();
		e.userId = application.userId();
		e.recipientEmail = application.recipientEmail();
		e.subject = application.subject();
		e.body = application.body();
		e.resumeAttached = application.resumeAttached();
		e.resumeName = application.resumeName();
		e.status = application.status();
		e.error = application.error();
		e.appliedAt = application.appliedAt();
		return e;
	}

	ExternalJobApplication toDomain() {
		return new ExternalJobApplication(id, externalJobId, userId, recipientEmail, subject, body, resumeAttached,
			resumeName, status, error, appliedAt);
	}
}
