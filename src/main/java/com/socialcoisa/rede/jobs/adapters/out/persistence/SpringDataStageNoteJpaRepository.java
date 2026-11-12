package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataStageNoteJpaRepository extends JpaRepository<StageNoteJpaEntity, Long> {

	List<StageNoteJpaEntity> findByApplicationIdOrderByCreatedAtAsc(Long applicationId);

}
