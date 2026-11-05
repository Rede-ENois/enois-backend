package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobStatus;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "jobs", indexes = {
		@Index(name = "idx_jobs_type", columnList = "type"),
		@Index(name = "idx_jobs_posted_at", columnList = "posted_at") })
class JobJpaEntity {

	@Id
	private String id;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false)
	private String company;

	@Column(length = 500)
	private String logo;

	private String location;

	@Column(nullable = false)
	String type;

	@Column(nullable = false)
	String level;

	private String salary;

	@Column(nullable = false, length = 3)
	private String currency;

	/** Valor mínimo obrigatório das vagas novas (nulo nas antigas, que usam `salary`). */
	@Column(name = "salary_min", precision = 12, scale = 2)
	private BigDecimal salaryMin;

	/** Teto opcional da faixa. */
	@Column(name = "salary_max", precision = 12, scale = 2)
	private BigDecimal salaryMax;

	/** HOUR | DAY | MONTH | YEAR; padrão MONTH na vaga nova. */
	@Column(name = "salary_period", length = 10)
	private String salaryPeriod;

	/** Texto que explica a vaga; obrigatório na criação/edição de vaga nova. */
	private String description;

	@Column(name = "community_agreed_at")
	private Instant communityAgreedAt;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "job_skills", joinColumns = @JoinColumn(name = "job_id"),
		indexes = @Index(name = "idx_job_skills_job", columnList = "job_id"))
	@Column(name = "skill", nullable = false)
	private List<String> skills = new ArrayList<>();

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "job_application_fields", joinColumns = @JoinColumn(name = "job_id"))
	@jakarta.persistence.OrderBy("position")
	private List<JobApplicationFieldEmbeddable> applicationFields = new ArrayList<>();

	@Column(name = "posted_by", nullable = false)
	private String postedBy;

	@Column(name = "posted_at", nullable = false)
	private Instant postedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private JobStatus status;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Column(name = "company_id")
	private String companyId;

	protected JobJpaEntity() {
	}

	static JobJpaEntity fromDomain(Job job) {
		JobJpaEntity e = new JobJpaEntity();
		e.id = job.id();
		e.title = job.title();
		e.company = job.company();
		e.logo = job.logo();
		e.location = job.location();
		e.type = job.type();
		e.level = job.level();
		e.salary = job.salary();
		e.currency = job.currency();
		e.salaryMin = job.salaryMin();
		e.salaryMax = job.salaryMax();
		e.salaryPeriod = job.salaryPeriod();
		e.description = job.description();
		e.communityAgreedAt = job.communityAgreedAt();
		e.skills = new ArrayList<>(job.skills());
		e.applicationFields = job.applicationFields().stream()
			.map(JobApplicationFieldEmbeddable::fromDomain)
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		e.postedBy = job.postedBy();
		e.postedAt = job.postedAt();
		e.updatedAt = job.updatedAt();
		e.status = job.status();
		e.closedAt = job.closedAt();
		e.companyId = job.companyId();
		return e;
	}

	Job toDomain() {
		return Job.restore(id, title, company, logo, location, type, level, salary, currency, salaryMin,
			salaryMax, salaryPeriod, description, skills,
			applicationFields.stream().map(JobApplicationFieldEmbeddable::toDomain).toList(), postedBy,
			communityAgreedAt, status, closedAt, postedAt, updatedAt, companyId);
	}

}
