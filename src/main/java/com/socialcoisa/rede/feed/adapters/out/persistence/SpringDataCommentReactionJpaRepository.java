package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCommentReactionJpaRepository extends JpaRepository<CommentReactionJpaEntity, String> {

	Optional<CommentReactionJpaEntity> findByCommentIdAndUserId(String commentId, String userId);

	List<CommentReactionJpaEntity> findByCommentIdInAndUserId(Collection<String> commentIds, String userId);

	List<CommentReactionJpaEntity> findByCommentIdOrderByCreatedAtDesc(String commentId);
}
