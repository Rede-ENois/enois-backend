package com.socialcoisa.rede.e2e.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataUserKeypairRepository extends JpaRepository<UserKeypairJpaEntity, String> {
	Optional<UserKeypairJpaEntity> findByUserIdAndActiveTrue(String userId);
	List<UserKeypairJpaEntity> findByUserId(String userId);

	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query("UPDATE UserKeypairJpaEntity k SET k.active = false WHERE k.userId = :userId AND k.active = true")
	void deactivateByUserId(@org.springframework.data.repository.query.Param("userId") String userId);
}
