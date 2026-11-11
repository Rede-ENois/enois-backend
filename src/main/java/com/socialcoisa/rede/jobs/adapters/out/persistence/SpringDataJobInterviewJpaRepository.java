package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataJobInterviewJpaRepository extends JpaRepository<JobInterviewJpaEntity, String> {

	List<JobInterviewJpaEntity> findByApplicationIdOrderByCreatedAtDesc(Long applicationId);

	Optional<JobInterviewJpaEntity> findByApplicationIdAndStatus(Long applicationId,
			com.socialcoisa.rede.jobs.domain.JobInterviewStatus status);

	List<JobInterviewJpaEntity> findByJobIdAndStatus(String jobId,
			com.socialcoisa.rede.jobs.domain.JobInterviewStatus status);

	List<JobInterviewJpaEntity> findByApplicationIdInAndStatus(java.util.Collection<Long> applicationIds,
			com.socialcoisa.rede.jobs.domain.JobInterviewStatus status);
}
