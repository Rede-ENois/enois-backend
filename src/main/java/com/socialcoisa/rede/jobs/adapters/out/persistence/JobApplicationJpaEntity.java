package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "job_applications",
	uniqueConstraints = @UniqueConstraint(name = "uk_job_application", columnNames = { "job_id", "user_id" }),
	indexes = {
		@Index(name = "idx_job_applications_job", columnList = "job_id"),
		@Index(name = "idx_job_applications_user", columnList = "user_id") })
class JobApplicationJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "job_id", nullable = false)
	private String jobId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(nullable = false)
	private String status;

	@Column(name = "applied_at", nullable = false)
	private Instant appliedAt;

	protected JobApplicationJpaEntity() {
	}

	static JobApplicationJpaEntity create(String jobId, String userId, Instant now) {
		JobApplicationJpaEntity e = new JobApplicationJpaEntity();
		e.jobId = jobId;
		e.userId = userId;
		e.status = "APPLIED";
		e.appliedAt = now;
		return e;
	}

}
