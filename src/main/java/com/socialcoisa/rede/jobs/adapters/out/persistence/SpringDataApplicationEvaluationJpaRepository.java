package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataApplicationEvaluationJpaRepository
		extends JpaRepository<ApplicationEvaluationJpaEntity, Long> {

	java.util.Optional<ApplicationEvaluationJpaEntity> findByApplicationIdAndAuthorId(long applicationId,
			String authorId);

	List<ApplicationEvaluationJpaEntity> findByApplicationIdOrderByUpdatedAtDesc(long applicationId);

}
