package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataReactionJpaRepository extends JpaRepository<ReactionJpaEntity, String> {

	boolean existsByPostIdAndUserId(String postId, String userId);

	Optional<ReactionJpaEntity> findByPostIdAndUserId(String postId, String userId);

	List<ReactionJpaEntity> findByPostIdInAndUserId(Collection<String> postIds, String userId);

	List<ReactionJpaEntity> findByPostIdOrderByCreatedAtDesc(String postId);
}
