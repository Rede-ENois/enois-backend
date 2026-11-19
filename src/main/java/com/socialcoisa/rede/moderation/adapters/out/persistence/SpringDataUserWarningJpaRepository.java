package com.socialcoisa.rede.moderation.adapters.out.persistence;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataUserWarningJpaRepository extends JpaRepository<UserWarningJpaEntity, String> {

	List<UserWarningJpaEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable page);

	long countByUserIdAndRevokedAtIsNull(String userId);
}
