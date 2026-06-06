package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.socialcoisa.rede.feed.domain.CommentStatus;

interface SpringDataCommentJpaRepository extends JpaRepository<CommentJpaEntity, String> {

	List<CommentJpaEntity> findByPostIdAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(String postId,
			CommentStatus status, Instant before, Pageable pageable);
}
