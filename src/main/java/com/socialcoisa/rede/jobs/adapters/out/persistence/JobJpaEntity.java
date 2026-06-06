package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.socialcoisa.rede.jobs.domain.Job;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
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

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "job_skills", joinColumns = @JoinColumn(name = "job_id"),
		indexes = @Index(name = "idx_job_skills_job", columnList = "job_id"))
	@Column(name = "skill", nullable = false)
	private List<String> skills = new ArrayList<>();

	@Column(name = "posted_by", nullable = false)
	private String postedBy;

	@Column(name = "posted_at", nullable = false)
	private Instant postedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

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
		e.skills = new ArrayList<>(job.skills());
		e.postedBy = job.postedBy();
		e.postedAt = job.postedAt();
		e.updatedAt = job.updatedAt();
		return e;
	}

	Job toDomain() {
		return Job.restore(id, title, company, logo, location, type, level, salary, skills, postedBy, postedAt, updatedAt);
	}

}
