package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataUserCredentialJpaRepository extends JpaRepository<UserCredentialJpaEntity, String> {

	Optional<UserCredentialJpaEntity> findByEmail(String email);

	boolean existsByEmail(String email);
}
