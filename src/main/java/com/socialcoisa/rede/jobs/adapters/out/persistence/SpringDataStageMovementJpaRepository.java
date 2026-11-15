package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataStageMovementJpaRepository extends JpaRepository<StageMovementJpaEntity, Long> {

	Optional<StageMovementJpaEntity> findFirstByApplicationIdOrderByMovedAtDesc(Long applicationId);

	List<StageMovementJpaEntity> findByApplicationIdOrderByMovedAtAsc(Long applicationId);

	List<StageMovementJpaEntity> findByJobIdOrderByMovedAtAsc(String jobId);

	@org.springframework.data.jpa.repository.Query("""
			select m from StageMovementJpaEntity m
			where m.jobId in :jobIds
			  and m.id in (select max(latest.id) from StageMovementJpaEntity latest group by latest.applicationId)
			""")
	List<StageMovementJpaEntity> findLatestPerApplicationForJobs(
			@org.springframework.data.repository.query.Param("jobIds") java.util.Collection<String> jobIds);

	@org.springframework.data.jpa.repository.Query("""
			select m from StageMovementJpaEntity m
			where m.applicationId in :applicationIds
			  and m.id in (select max(latest.id) from StageMovementJpaEntity latest group by latest.applicationId)
			""")
	List<StageMovementJpaEntity> findLatestPerApplication(
			@org.springframework.data.repository.query.Param("applicationIds") java.util.Collection<Long> applicationIds);

}
