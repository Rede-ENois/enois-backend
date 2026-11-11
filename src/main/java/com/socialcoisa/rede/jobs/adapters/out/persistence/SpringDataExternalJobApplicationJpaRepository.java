package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataExternalJobApplicationJpaRepository
		extends JpaRepository<ExternalJobApplicationJpaEntity, String> {

	Optional<ExternalJobApplicationJpaEntity> findByExternalJobIdAndUserId(String externalJobId, String userId);

	List<ExternalJobApplicationJpaEntity> findByUserIdOrderByAppliedAtDesc(String userId, Pageable pageable);
}
