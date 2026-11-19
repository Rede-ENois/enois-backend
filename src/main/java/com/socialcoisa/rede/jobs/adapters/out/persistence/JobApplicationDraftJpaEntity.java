package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Rascunho da candidatura: uma linha por (vaga, candidato); o autosave grava por cima. */
@Entity
@Table(name = "job_application_drafts")
@IdClass(JobApplicationDraftJpaEntity.Pk.class)
class JobApplicationDraftJpaEntity {

	static class Pk implements java.io.Serializable {

		String jobId;
		String userId;
	}

	@Id
	@Column(name = "job_id", nullable = false, length = 255)
	private String jobId;

	@Id
	@Column(name = "user_id", nullable = false, length = 255)
	private String userId;

	@Column(name = "answers_json", nullable = false, columnDefinition = "mediumtext")
	private String answersJson;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected JobApplicationDraftJpaEntity() {
	}

	String getJobId() {
		return jobId;
	}

	void setJobId(String jobId) {
		this.jobId = jobId;
	}

	String getUserId() {
		return userId;
	}

	void setUserId(String userId) {
		this.userId = userId;
	}

	String getAnswersJson() {
		return answersJson;
	}

	void setAnswersJson(String answersJson) {
		this.answersJson = answersJson;
	}

	Instant getUpdatedAt() {
		return updatedAt;
	}

	void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}
}

interface SpringDataJobApplicationDraftJpaRepository extends JpaRepository<JobApplicationDraftJpaEntity, Long> {

	Optional<JobApplicationDraftJpaEntity> findByJobIdAndUserId(String jobId, String userId);

	void deleteByJobIdAndUserId(String jobId, String userId);
}
