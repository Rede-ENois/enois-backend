package com.socialcoisa.rede.jobs.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class JobService {

	private final JobRepository repository;
	private final UserProfileRepository profileRepository;
	private final Clock clock;

	public JobService(JobRepository repository, UserProfileRepository profileRepository, Clock clock) {
		this.repository = repository;
		this.profileRepository = profileRepository;
		this.clock = clock;
	}

	@Transactional
	public Job create(String title, String company, String logo, String location, String type, String level,
			String salary, List<String> skills, String userId) {
		int reputation = profileRepository.findById(userId)
			.map(p -> p.reputation())
			.orElse(0);
		if (reputation < 300) {
			throw new ApiException(403, "LOW_REPUTATION", "Reputacao insuficiente para criar vagas.");
		}
		String id = "job_" + UUID.randomUUID().toString().replace("-", "");
		Job job = Job.create(id, title, company, logo, location, type, level, salary, skills, userId, clock.instant());
		repository.save(job);
		return job;
	}

	@Transactional
	public Job update(String jobId, String title, String company, String logo, String location, String type, String level,
			String salary, List<String> skills, String userId) {
		Job existing = repository.findById(jobId)
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga nao encontrada."));
		if (!existing.postedBy().equals(userId)) {
			throw new ApiException(403, "NOT_OWNER", "Voce nao pode editar uma vaga que nao criou.");
		}
		Job updated = Job.restore(jobId, title, company, logo, location, type, level, salary, skills, userId,
			existing.postedAt(), clock.instant());
		repository.save(updated);
		return updated;
	}

	@Transactional
	public void delete(String jobId, String userId) {
		Job existing = repository.findById(jobId)
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga nao encontrada."));
		if (!existing.postedBy().equals(userId)) {
			throw new ApiException(403, "NOT_OWNER", "Voce nao pode excluir uma vaga que nao criou.");
		}
		repository.deleteById(jobId);
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
