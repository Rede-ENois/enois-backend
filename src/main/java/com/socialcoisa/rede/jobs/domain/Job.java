package com.socialcoisa.rede.jobs.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class Job {

	private final String id;
	private final String title;
	private final String company;
	private final String logo;
	private final String location;
	private final String type;
	private final String level;
	/** Texto livre legado (vagas anteriores); vaga nova usa a faixa estruturada abaixo. */
	private final String salary;
	private final String currency;
	/** Valor mínimo obrigatório (> 0) das vagas novas; nulo nas antigas. */
	private final BigDecimal salaryMin;
	/** Teto opcional da faixa; nunca abaixo do mínimo. */
	private final BigDecimal salaryMax;
	/** HOUR | DAY | MONTH | YEAR; padrão MONTH. */
	private final String salaryPeriod;
	private final String description;
	private final List<String> skills;
	private final List<JobApplicationField> applicationFields;
	private final String postedBy;
	private final Instant communityAgreedAt;
	private final Instant postedAt;
	private final Instant updatedAt;
	/** Estado do processo seletivo; comeca OPEN. */
	private JobStatus status;
	private Instant closedAt;
	/** The company page the job belongs to (null = not linked to a page). */
	private final String companyId;

	private Job(String id, String title, String company, String logo, String location, String type, String level,
			String salary, String currency, BigDecimal salaryMin, BigDecimal salaryMax, String salaryPeriod,
			String description, List<String> skills, List<JobApplicationField> applicationFields,
			String postedBy, Instant communityAgreedAt, JobStatus status, Instant closedAt, Instant postedAt,
			Instant updatedAt, String companyId) {
		this.id = id;
		this.title = title;
		this.company = company;
		this.logo = logo;
		this.location = location;
		this.type = type;
		this.level = level;
		this.salary = salary;
		this.currency = currency;
		this.salaryMin = salaryMin;
		this.salaryMax = salaryMax;
		this.salaryPeriod = salaryPeriod;
		this.description = description;
		this.skills = skills;
		this.applicationFields = List.copyOf(applicationFields);
		this.postedBy = postedBy;
		this.communityAgreedAt = communityAgreedAt;
		this.postedAt = postedAt;
		this.updatedAt = updatedAt;
		this.status = status == null ? JobStatus.OPEN : status;
		this.closedAt = closedAt;
		this.companyId = companyId;
	}

	public static Job create(String id, String title, String company, String logo, String location, String type,
			String level, String salary, String currency, BigDecimal salaryMin, BigDecimal salaryMax,
			String salaryPeriod, String description, List<String> skills,
			List<JobApplicationField> applicationFields, String postedBy, Instant communityAgreedAt, Instant now) {
		return new Job(id, title, company, logo, location, type, level, salary, currency, salaryMin, salaryMax,
			salaryPeriod, description, List.copyOf(skills), applicationFields, postedBy, communityAgreedAt,
			JobStatus.OPEN, null, now, now, null);
	}

	public static Job restore(String id, String title, String company, String logo, String location, String type,
			String level, String salary, String currency, BigDecimal salaryMin, BigDecimal salaryMax,
			String salaryPeriod, String description, List<String> skills,
			List<JobApplicationField> applicationFields, String postedBy, Instant communityAgreedAt,
			JobStatus status, Instant closedAt, Instant postedAt, Instant updatedAt, String companyId) {
		return new Job(id, title, company, logo, location, type, level, salary, currency, salaryMin, salaryMax,
			salaryPeriod, description, skills, applicationFields, postedBy, communityAgreedAt, status, closedAt,
			postedAt, updatedAt, companyId);
	}

	public String id() { return id; }

	public String title() { return title; }

	public String company() { return company; }

	public String logo() { return logo; }

	public String location() { return location; }

	public String type() { return type; }

	public String level() { return level; }

	public String salary() { return salary; }

	/** Valor mínimo obrigatório das vagas novas (nulo nas vagas antigas, que usam `salary`). */
	public BigDecimal salaryMin() { return salaryMin; }

	/** Teto opcional da faixa (nulo = "a partir de"). */
	public BigDecimal salaryMax() { return salaryMax; }

	/** HOUR | DAY | MONTH | YEAR; vaga antiga sem valor estruturado vem nulo. */
	public String salaryPeriod() { return salaryPeriod; }

	public String description() { return description; }

	/** Codigo ISO da moeda da faixa salarial (BRL, USD, EUR). */
	public String currency() { return currency; }

	/** Instante em que o autor declarou que a vaga e real e respeita as regras da comunidade. */
	public Instant communityAgreedAt() { return communityAgreedAt; }

	public List<String> skills() { return skills; }

	/** Campos que o autor definiu para o formulario de candidatura (pode ser vazio). */
	public List<JobApplicationField> applicationFields() { return applicationFields; }

	public String postedBy() { return postedBy; }

	public Instant postedAt() { return postedAt; }

	public Instant updatedAt() { return updatedAt; }

	public JobStatus status() { return status; }

	/** Quando o autor encerrou o processo seletivo (nulo enquanto aberta). */
	public Instant closedAt() { return closedAt; }

	public boolean isOpen() { return status == JobStatus.OPEN; }

	/**
	 * Encerra a vaga registrando o instante. Encerrar nao apaga nem altera candidaturas: elas
	 * continuam disponiveis para a triagem de quem publicou.
	 */
	public Job closed(Instant at) {
		return new Job(id, title, company, logo, location, type, level, salary, currency, salaryMin, salaryMax,
			salaryPeriod, description, skills, applicationFields, postedBy, communityAgreedAt, JobStatus.CLOSED,
			at, postedAt, updatedAt, companyId);
	}

	public String companyId() { return companyId; }

	/** The same job linked to a company page. */
	public Job withCompany(String pageId) {
		return new Job(id, title, company, logo, location, type, level, salary, currency, salaryMin, salaryMax,
			salaryPeriod, description, skills, applicationFields, postedBy, communityAgreedAt, status, closedAt,
			postedAt, updatedAt, pageId);
	}

}
