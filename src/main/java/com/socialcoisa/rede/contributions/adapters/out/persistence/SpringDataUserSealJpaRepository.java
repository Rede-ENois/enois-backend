package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialcoisa.rede.contributions.domain.SealStatus;
import com.socialcoisa.rede.contributions.domain.SealType;

interface SpringDataUserSealJpaRepository extends JpaRepository<UserSealJpaEntity, String> {

	Optional<UserSealJpaEntity> findByUserIdAndSealType(String userId, SealType sealType);

	List<UserSealJpaEntity> findByUserIdAndStatus(String userId, SealStatus status);
}
