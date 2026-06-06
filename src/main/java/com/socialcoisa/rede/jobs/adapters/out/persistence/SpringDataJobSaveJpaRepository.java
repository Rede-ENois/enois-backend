package com.socialcoisa.rede.jobs.adapters.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataJobSaveJpaRepository extends JpaRepository<JobSaveJpaEntity, Long> {

	boolean existsByJobIdAndUserId(String jobId, String userId);

	void deleteByJobIdAndUserId(String jobId, String userId);

	void deleteByJobId(String jobId);

}
