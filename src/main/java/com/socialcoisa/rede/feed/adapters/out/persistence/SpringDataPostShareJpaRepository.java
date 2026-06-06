package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPostShareJpaRepository extends JpaRepository<PostShareJpaEntity, String> {

	boolean existsByPostIdAndUserId(String postId, String userId);

	Optional<PostShareJpaEntity> findByPostIdAndUserId(String postId, String userId);
}
