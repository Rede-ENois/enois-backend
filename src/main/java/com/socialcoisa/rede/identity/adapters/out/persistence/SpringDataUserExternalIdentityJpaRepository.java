package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataUserExternalIdentityJpaRepository extends JpaRepository<UserExternalIdentityJpaEntity, Long> {

	Optional<UserExternalIdentityJpaEntity> findByProviderAndExternalUserId(String provider, String externalUserId);

	boolean existsByProviderAndExternalUserId(String provider, String externalUserId);
}
