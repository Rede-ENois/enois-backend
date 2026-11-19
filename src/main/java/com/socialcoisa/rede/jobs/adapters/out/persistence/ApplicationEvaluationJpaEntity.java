package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;
import java.util.Map;

import com.socialcoisa.rede.jobs.domain.ApplicationEvaluation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "job_application_evaluations")
class ApplicationEvaluationJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "application_id", nullable = false)
	private long applicationId;

	@Column(name = "author_id", nullable = false)
	private String authorId;

	/** Critérios e notas como JSON — a equipe define os critérios por avaliação, sem configurar nada. */
	@Column(name = "criteria_json", nullable = false, columnDefinition = "mediumtext")
	private String criteriaJson;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private ApplicationEvaluation.Verdict verdict;

	@Column(length = 2000)
	private String summary;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ApplicationEvaluationJpaEntity() {
	}

	/** Grava a avaliação do domínio numa linha nova (id nulo) ou reavaliada (linha existente). */
	void applyFrom(ApplicationEvaluation evaluation, String criteriaJson) {
		this.applicationId = evaluation.applicationId();
		this.authorId = evaluation.authorId();
		this.criteriaJson = criteriaJson;
		this.verdict = evaluation.verdict();
		this.summary = evaluation.summary();
		if (this.createdAt == null) {
			this.createdAt = evaluation.createdAt();
		}
		this.updatedAt = evaluation.updatedAt();
	}

	String criteriaJson() {
		return criteriaJson;
	}

	ApplicationEvaluation toDomain(Map<String, Integer> criteria) {
		return new ApplicationEvaluation(id, applicationId, authorId, criteria, verdict, summary, createdAt,
			updatedAt);
	}

}
