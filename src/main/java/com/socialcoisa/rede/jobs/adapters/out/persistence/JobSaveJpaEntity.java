package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "job_saves",
	uniqueConstraints = @UniqueConstraint(name = "uk_job_save", columnNames = { "job_id", "user_id" }))
class JobSaveJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "job_id", nullable = false)
	private String jobId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "saved_at", nullable = false)
	private Instant savedAt;

	protected JobSaveJpaEntity() {
	}

	static JobSaveJpaEntity create(String jobId, String userId, Instant now) {
		JobSaveJpaEntity e = new JobSaveJpaEntity();
		e.jobId = jobId;
		e.userId = userId;
		e.savedAt = now;
		return e;
	}

}
