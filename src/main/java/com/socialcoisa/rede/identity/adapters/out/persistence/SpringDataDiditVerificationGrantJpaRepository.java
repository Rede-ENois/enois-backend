package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataDiditVerificationGrantJpaRepository extends JpaRepository<DiditVerificationGrantJpaEntity, String> {

	Optional<DiditVerificationGrantJpaEntity> findByUserIdAndRevokedAtIsNull(String userId);
}
