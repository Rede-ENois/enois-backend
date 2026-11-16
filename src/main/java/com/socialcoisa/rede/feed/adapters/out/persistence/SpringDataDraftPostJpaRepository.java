package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataDraftPostJpaRepository extends JpaRepository<DraftPostJpaEntity, String> {

	Optional<DraftPostJpaEntity> findByUserId(String userId);

	List<DraftPostJpaEntity> findByUserIdOrderByUpdatedAtDesc(String userId);
}
