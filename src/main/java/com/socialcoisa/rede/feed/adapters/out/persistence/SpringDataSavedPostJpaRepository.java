package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataSavedPostJpaRepository extends JpaRepository<SavedPostJpaEntity, String> {

	boolean existsByPostIdAndUserId(String postId, String userId);

	Optional<SavedPostJpaEntity> findByPostIdAndUserId(String postId, String userId);
}
