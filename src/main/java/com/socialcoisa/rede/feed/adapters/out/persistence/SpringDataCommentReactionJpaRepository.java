package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataCommentReactionJpaRepository extends JpaRepository<CommentReactionJpaEntity, String> {

	Optional<CommentReactionJpaEntity> findByCommentIdAndUserId(String commentId, String userId);

	List<CommentReactionJpaEntity> findByCommentIdInAndUserId(Collection<String> commentIds, String userId);

	List<CommentReactionJpaEntity> findByCommentIdOrderByCreatedAtDesc(String commentId);

	List<CommentReactionJpaEntity> findByCommentIdIn(Collection<String> commentIds);

	/** 1 when inserted, 0 when this person already has a reaction on the comment (unique key). */
	@Modifying
	@Query(value = "insert ignore into comment_reactions (id, comment_id, user_id, reaction_type, created_at)"
		+ " values (:id, :commentId, :userId, :type, :createdAt)", nativeQuery = true)
	int insertIgnoringDuplicate(@Param("id") String id, @Param("commentId") String commentId,
			@Param("userId") String userId, @Param("type") String type, @Param("createdAt") Instant createdAt);

	@Modifying
	@Query("delete from CommentReactionJpaEntity r where r.id = :id")
	int deleteRowById(@Param("id") String id);

	@Modifying
	@Query("delete from CommentReactionJpaEntity r where r.commentId = :commentId and r.userId = :userId")
	int deleteByCommentAndUser(@Param("commentId") String commentId, @Param("userId") String userId);
}
