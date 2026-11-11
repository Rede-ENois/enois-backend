package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.jobs.domain.ExternalJob;
import com.socialcoisa.rede.jobs.domain.ExternalJobDetails;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "external_jobs",
	uniqueConstraints = @UniqueConstraint(name = "uk_external_jobs_source",
		columnNames = { "platform", "external_id" }),
	indexes = @Index(name = "idx_external_jobs_checked", columnList = "last_checked_at"))
class ExternalJobJpaEntity {

	@Id
	private String id;

	@Column(nullable = false)
	private String platform;

	@Column(name = "external_id", nullable = false)
	private String externalId;

	@Column(nullable = false, length = 500)
	private String title;

	private String company;

	private String location;

	private String salary;

	@Column(length = 8)
	private String currency;

	@Column(nullable = false, length = 1000)
	private String url;

	@Column(name = "imported_at", nullable = false)
	private Instant importedAt;

	@Column(name = "last_checked_at", nullable = false)
	private Instant lastCheckedAt;

	@Column(name = "external_status", nullable = false)
	private String externalStatus = ExternalJob.FOUND;

	@Column(name = "canonical_url", length = 1000)
	private String canonicalUrl;

	@Column(name = "apply_email", length = 320)
	private String applyEmail;

	/** Autor do cadastro manual (nulo nas importadas de agregador). */
	@Column(name = "submitted_by")
	private String submittedBy;

	/** Detalhes do cadastro manual: atividades e faixa salarial (importadas ficam nulas). */
	@Column(length = 5000)
	private String description;

	@Column(name = "salary_min", length = 50)
	private String salaryMin;

	@Column(name = "salary_max", length = 50)
	private String salaryMax;

	@Column(name = "salary_period", length = 20)
	private String salaryPeriod;

	/** Quem cadastrou declarou que a vaga não informa salário (não é o mesmo que campo vazio). */
	@Column(name = "salary_not_informed", nullable = false)
	private boolean salaryNotInformed;

	// --- Detalhes estruturados (V269). Nulo = a fonte não informou; nada é deduzido. ---

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(name = "employment_type", length = 40)
	private String employmentType;

	@Column(length = 20)
	private String level;

	@Column(name = "work_model", length = 20)
	private String workModel;

	@Column(length = 120)
	private String city;

	@Column(length = 60)
	private String state;

	@Column(length = 60)
	private String country;

	@Column(name = "salary_min_amount", precision = 12, scale = 2)
	private java.math.BigDecimal salaryMinAmount;

	@Column(name = "salary_max_amount", precision = 12, scale = 2)
	private java.math.BigDecimal salaryMaxAmount;

	@Column(name = "salary_currency", length = 8)
	private String salaryCurrency;

	@Column(name = "salary_period_code", length = 10)
	private String salaryPeriodCode;

	/** Link de atribuição exigido pelos termos da fonte (crédito com link de volta). */
	@Column(name = "attribution_url", length = 1000)
	private String attributionUrl;

	@Column(name = "last_seen_at")
	private Instant lastSeenAt;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "removed_at")
	private Instant removedAt;

	protected ExternalJobJpaEntity() {
	}

	static ExternalJobJpaEntity fromDomain(ExternalJob job) {
		ExternalJobJpaEntity e = new ExternalJobJpaEntity();
		e.id = job.id();
		e.platform = job.platform();
		e.externalId = job.externalId();
		e.title = job.title();
		e.company = job.company();
		e.location = job.location();
		e.salary = job.salary();
		e.currency = job.currency();
		e.url = job.url();
		e.importedAt = job.importedAt();
		e.lastCheckedAt = job.lastCheckedAt();
		e.externalStatus = job.status() == null ? ExternalJob.FOUND : job.status();
		e.canonicalUrl = job.canonicalUrl();
		e.applyEmail = job.applyEmail();
		e.submittedBy = job.submittedBy();
		e.description = job.description();
		e.salaryMin = job.salaryMin();
		e.salaryMax = job.salaryMax();
		e.salaryPeriod = job.salaryPeriod();
		e.salaryNotInformed = job.salaryNotInformed();
		ExternalJobDetails details = job.details() == null ? ExternalJobDetails.empty() : job.details();
		e.publishedAt = details.publishedAt();
		e.employmentType = details.employmentType();
		e.level = details.level();
		e.workModel = details.workModel();
		e.city = details.city();
		e.state = details.state();
		e.country = details.country();
		e.salaryMinAmount = details.salaryMinAmount();
		e.salaryMaxAmount = details.salaryMaxAmount();
		e.salaryCurrency = details.salaryCurrency();
		e.salaryPeriodCode = details.salaryPeriodCode();
		e.attributionUrl = details.attributionUrl();
		e.lastSeenAt = details.lastSeenAt();
		e.expiresAt = details.expiresAt();
		e.removedAt = details.removedAt();
		return e;
	}

	ExternalJob toDomain() {
		return new ExternalJob(id, platform, externalId, title, company, location, salary, currency, url,
			importedAt, lastCheckedAt, externalStatus, canonicalUrl, applyEmail, submittedBy, description,
			salaryMin, salaryMax, salaryPeriod, salaryNotInformed,
			new ExternalJobDetails(publishedAt, employmentType, level, workModel, city, state, country,
				salaryMinAmount, salaryMaxAmount, salaryCurrency, salaryPeriodCode, attributionUrl, lastSeenAt,
				expiresAt, removedAt));
	}
}
