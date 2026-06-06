package com.socialcoisa.rede.jobs.adapters.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataJobApplicationJpaRepository extends JpaRepository<JobApplicationJpaEntity, Long> {

	boolean existsByJobIdAndUserId(String jobId, String userId);

	void deleteByJobIdAndUserId(String jobId, String userId);

	void deleteByJobId(String jobId);

}
