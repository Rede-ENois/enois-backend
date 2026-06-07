package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataUserProfileJpaRepository extends JpaRepository<UserProfileJpaEntity, String> {

	Optional<UserProfileJpaEntity> findByUsername(String username);

	Optional<UserProfileJpaEntity> findByEmail(String email);

	boolean existsByUsername(String username);

	boolean existsByEmail(String email);

	List<UserProfileJpaEntity> findByOrderByReputationDesc();
}
