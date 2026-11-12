package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataJobApplicationJpaRepository extends JpaRepository<JobApplicationJpaEntity, Long> {

	boolean existsByJobIdAndUserId(String jobId, String userId);

	void deleteByJobIdAndUserId(String jobId, String userId);

	void deleteByJobId(String jobId);

	int countByJobId(String jobId);

	/** Candidaturas ainda em APPLIED (o empregador nunca deu retorno) — item 7 do backlog. */
	int countByJobIdAndStatus(String jobId, com.socialcoisa.rede.jobs.domain.JobApplicationStatus status);

	List<JobApplicationJpaEntity> findByJobIdOrderByAppliedAtDesc(String jobId, Pageable pageable);

	/** As candidaturas de uma pessoa (a tela "minhas candidaturas", com estado e retorno). */
	List<JobApplicationJpaEntity> findByUserIdOrderByAppliedAtDesc(String userId, Pageable pageable);

	@Query("SELECT a.jobId FROM JobApplicationJpaEntity a WHERE a.userId = :userId AND a.jobId IN :jobIds")
	List<String> findJobIdsByUserIdAndJobIdIn(@Param("userId") String userId,
			@Param("jobIds") java.util.Collection<String> jobIds);

	@Query("SELECT a.jobId FROM JobApplicationJpaEntity a WHERE a.userId = :userId ORDER BY a.appliedAt DESC")
	List<String> findJobIdsByUserId(@Param("userId") String userId, Pageable pageable);

	@Query("SELECT a.jobId, COUNT(a) FROM JobApplicationJpaEntity a WHERE a.jobId IN :jobIds GROUP BY a.jobId")
	List<Object[]> countByJobIdGrouped(@Param("jobIds") java.util.Collection<String> jobIds);

	@Query("""
			SELECT a.jobId, COUNT(a) FROM JobApplicationJpaEntity a
			WHERE a.jobId IN :jobIds AND a.status = :status GROUP BY a.jobId
			""")
	List<Object[]> countByJobIdAndStatusGrouped(@Param("jobIds") java.util.Collection<String> jobIds,
			@Param("status") com.socialcoisa.rede.jobs.domain.JobApplicationStatus status);

}
