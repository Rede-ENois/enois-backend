package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPostRepostJpaRepository extends JpaRepository<PostRepostJpaEntity, Long> {

	Optional<PostRepostJpaEntity> findByUserIdAndPostId(String userId, String postId);

	List<PostRepostJpaEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

	List<PostRepostJpaEntity> findByPostIdIn(java.util.Collection<String> postIds);

	void deleteByUserIdAndPostId(String userId, String postId);

	List<PostRepostJpaEntity> findByUserIdInOrderByCreatedAtDesc(java.util.Collection<String> userIds);
}
