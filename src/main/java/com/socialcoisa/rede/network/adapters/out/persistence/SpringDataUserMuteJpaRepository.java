package com.socialcoisa.rede.network.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataUserMuteJpaRepository extends JpaRepository<UserMuteJpaEntity, Long> {

	Optional<UserMuteJpaEntity> findByMuterIdAndMutedId(String muterId, String mutedId);

	List<UserMuteJpaEntity> findByMuterIdOrderByCreatedAtDesc(String muterId);
}
