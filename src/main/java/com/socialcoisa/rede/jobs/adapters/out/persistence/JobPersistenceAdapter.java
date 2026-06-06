package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import com.socialcoisa.rede.jobs.domain.Job;

@Repository
class JobPersistenceAdapter implements JobRepository {

	private final SpringDataJobJpaRepository springData;
	private final SpringDataJobApplicationJpaRepository applicationRepo;
	private final SpringDataJobSaveJpaRepository saveRepo;
	private final Clock clock;

	JobPersistenceAdapter(SpringDataJobJpaRepository springData,
			SpringDataJobApplicationJpaRepository applicationRepo,
			SpringDataJobSaveJpaRepository saveRepo, Clock clock) {
		this.springData = springData;
		this.applicationRepo = applicationRepo;
		this.saveRepo = saveRepo;
		this.clock = clock;
	}

	@Override
	public void save(Job job) {
		springData.save(JobJpaEntity.fromDomain(job));
	}

	@Override
	public Optional<Job> findById(String id) {
		return springData.findById(id).map(JobJpaEntity::toDomain);
	}

	@Override
	public List<Job> findAll(String query, String type, int limit) {
		List<JobJpaEntity> entities;
		if (StringUtils.hasText(query)) {
			entities = springData.searchByQuery(query);
		} else if (StringUtils.hasText(type)) {
			entities = springData.findByTypeOrderByPostedAtDesc(type);
		} else {
			entities = springData.findAllByOrderByPostedAtDesc();
		}
		return entities.stream().map(JobJpaEntity::toDomain).limit(limit).toList();
	}

	@Override
	public void saveApplication(String jobId, String userId) {
		applicationRepo.save(JobApplicationJpaEntity.create(jobId, userId, clock.instant()));
	}

	@Override
	public void removeApplication(String jobId, String userId) {
		applicationRepo.deleteByJobIdAndUserId(jobId, userId);
	}

	@Override
	public boolean hasApplied(String jobId, String userId) {
		return applicationRepo.existsByJobIdAndUserId(jobId, userId);
	}

	@Override
	public void saveBookmark(String jobId, String userId) {
		saveRepo.save(JobSaveJpaEntity.create(jobId, userId, clock.instant()));
	}

	@Override
	public void removeBookmark(String jobId, String userId) {
		saveRepo.deleteByJobIdAndUserId(jobId, userId);
	}

	@Override
	public boolean hasBookmarked(String jobId, String userId) {
		return saveRepo.existsByJobIdAndUserId(jobId, userId);
	}

}
