package com.socialcoisa.rede.jobs.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class JobService {

	private final JobRepository repository;
	private final Clock clock;

	public JobService(JobRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional
	public Job create(String title, String company, String logo, String location, String type, String level,
			String salary, List<String> skills, String userId) {
		String id = "job_" + UUID.randomUUID().toString().replace("-", "");
		Job job = Job.create(id, title, company, logo, location, type, level, salary, skills, userId, clock.instant());
		repository.save(job);
		return job;
	}

	@Transactional
	public void apply(String jobId, String userId) {
		repository.findById(jobId)
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga nao encontrada."));
		if (repository.hasApplied(jobId, userId)) {
			throw new ApiException(409, "ALREADY_APPLIED", "Voce ja se candidatou a esta vaga.");
		}
		repository.saveApplication(jobId, userId);
	}

	@Transactional
	public void save(String jobId, String userId) {
		if (repository.hasBookmarked(jobId, userId)) {
			throw new ApiException(409, "ALREADY_SAVED", "Vaga ja salva.");
		}
		repository.saveBookmark(jobId, userId);
	}

	@Transactional
	public void removeSave(String jobId, String userId) {
		if (!repository.hasBookmarked(jobId, userId)) {
			throw new ApiException(404, "NOT_SAVED", "Vaga nao estava salva.");
		}
		repository.removeBookmark(jobId, userId);
	}

	public List<Job> list(String query, String type, int limit) {
		return repository.findAll(query, type, limit);
	}

	public Job getById(String id) {
		return repository.findById(id)
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga nao encontrada."));
	}

}
