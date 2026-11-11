package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.jobs.application.port.out.JobInterviewRepository;
import com.socialcoisa.rede.jobs.domain.JobInterview;
import com.socialcoisa.rede.jobs.domain.JobInterviewStatus;

@Repository
class JobInterviewPersistenceAdapter implements JobInterviewRepository {

	private final SpringDataJobInterviewJpaRepository springData;

	JobInterviewPersistenceAdapter(SpringDataJobInterviewJpaRepository springData) {
		this.springData = springData;
	}

	@Override
	public void save(JobInterview interview) {
		springData.save(JobInterviewJpaEntity.fromDomain(interview));
	}

	@Override
	public Optional<JobInterview> findById(String id) {
		return springData.findById(id).map(JobInterviewJpaEntity::toDomain);
	}

	@Override
	public Optional<JobInterview> findScheduledByApplication(String applicationId) {
		return springData.findByApplicationIdAndStatus(Long.valueOf(applicationId), JobInterviewStatus.SCHEDULED)
			.map(JobInterviewJpaEntity::toDomain);
	}

	@Override
	public List<JobInterview> findScheduledByJob(String jobId) {
		return springData.findByJobIdAndStatus(jobId, JobInterviewStatus.SCHEDULED).stream()
			.map(JobInterviewJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<JobInterview> findByApplication(String applicationId) {
		return springData.findByApplicationIdOrderByCreatedAtDesc(Long.valueOf(applicationId)).stream()
			.map(JobInterviewJpaEntity::toDomain)
			.toList();
	}
}
