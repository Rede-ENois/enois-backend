package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataSelectionStageJpaRepository extends JpaRepository<SelectionStageJpaEntity, String> {

	List<SelectionStageJpaEntity> findByJobIdOrderByPositionAsc(String jobId);

}
