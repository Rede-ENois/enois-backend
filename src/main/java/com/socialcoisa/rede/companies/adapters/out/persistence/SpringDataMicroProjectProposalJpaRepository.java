package com.socialcoisa.rede.companies.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMicroProjectProposalJpaRepository extends JpaRepository<MicroProjectProposalJpaEntity, String> {

	@org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@org.springframework.data.jpa.repository.Query("select p from MicroProjectProposalJpaEntity p where p.projectId = :projectId")
	List<MicroProjectProposalJpaEntity> findForUpdate(@org.springframework.data.repository.query.Param("projectId") String projectId);

	boolean existsByProjectIdAndUserId(String projectId, String userId);

	List<MicroProjectProposalJpaEntity> findByProjectIdOrderByProposedAtAsc(String projectId);

	List<MicroProjectProposalJpaEntity> findTop20ByUserIdOrderByProposedAtDesc(String userId);
}
