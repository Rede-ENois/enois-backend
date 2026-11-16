package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Modelos de processo seletivo, ordenados do mais recente para o mais antigo. */
interface SpringDataSelectionProcessTemplateJpaRepository
		extends JpaRepository<SelectionProcessTemplateJpaEntity, String> {

	@Query("select t from SelectionProcessTemplateJpaEntity t where t.ownerId = :ownerId "
		+ "order by t.updatedAt desc, t.id desc")
	List<SelectionProcessTemplateJpaEntity> findPageByOwner(@Param("ownerId") String ownerId,
		org.springframework.data.domain.Pageable pageable);

	Optional<SelectionProcessTemplateJpaEntity> findByOwnerIdAndNameIgnoreCase(String ownerId, String name);
}
