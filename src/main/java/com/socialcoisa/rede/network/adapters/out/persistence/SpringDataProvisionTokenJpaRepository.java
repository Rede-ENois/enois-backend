package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataProvisionTokenJpaRepository extends JpaRepository<ProvisionTokenJpaEntity, String> {

	@Modifying
	@Query("update ProvisionTokenJpaEntity t set t.usedAt = :now where t.tokenHash = :hash and t.usedAt is null")
	int markUsed(@Param("hash") String hash, @Param("now") Instant now);

	Optional<ProvisionTokenJpaEntity> findByTokenHash(String tokenHash);

	@Query("select count(t) from ProvisionTokenJpaEntity t where t.ownerUserId = :owner and t.usedAt is null "
			+ "and t.expiresAt > :now")
	long countAliveForOwner(@Param("owner") String owner, @Param("now") Instant now);
}
