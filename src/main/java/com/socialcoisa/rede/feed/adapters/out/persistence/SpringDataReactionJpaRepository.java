package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataReactionJpaRepository extends JpaRepository<ReactionJpaEntity, String> {

	boolean existsByPostIdAndUserId(String postId, String userId);

	Optional<ReactionJpaEntity> findByPostIdAndUserId(String postId, String userId);

	List<ReactionJpaEntity> findByPostIdInAndUserId(Collection<String> postIds, String userId);

	List<ReactionJpaEntity> findByPostIdAndCreatedAtBeforeOrderByCreatedAtDesc(String postId, java.time.Instant before,
		org.springframework.data.domain.Pageable page);

	/** Post IDs que o usuario reagiu, do mais recente ao mais antigo. */
	@org.springframework.data.jpa.repository.Query(
		"SELECT r.postId FROM ReactionJpaEntity r WHERE r.userId = :userId ORDER BY r.createdAt DESC")
	List<String> findPostIdsByUserId(@org.springframework.data.repository.query.Param("userId") String userId,
		org.springframework.data.domain.Pageable pageable);

	/** Rows of (postId, reactionType, count). `excluded` must not be empty (NOT IN () is invalid SQL). */
	@org.springframework.data.jpa.repository.Query(
		"SELECT r.postId, r.reactionType, COUNT(r) FROM ReactionJpaEntity r "
		+ "WHERE r.postId IN :postIds AND r.userId NOT IN :excluded GROUP BY r.postId, r.reactionType")
	List<Object[]> countByPostIdsGroupedByType(
		@org.springframework.data.repository.query.Param("postIds") Collection<String> postIds,
		@org.springframework.data.repository.query.Param("excluded") Collection<String> excluded);
}
