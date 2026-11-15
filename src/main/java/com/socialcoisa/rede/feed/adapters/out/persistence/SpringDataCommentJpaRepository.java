package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.socialcoisa.rede.feed.domain.CommentStatus;

interface SpringDataCommentJpaRepository extends JpaRepository<CommentJpaEntity, String> {

	List<CommentJpaEntity> findByPostIdAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(String postId,
			CommentStatus status, Instant before, Pageable pageable);

	java.util.Optional<CommentJpaEntity> findFirstByPostIdAndStatusAndPinnedTrueOrderByCreatedAtDesc(String postId,
			CommentStatus status);

	List<CommentJpaEntity> findByPostIdAndStatusAndPinnedFalseAndCreatedAtBeforeOrderByCreatedAtDesc(String postId,
			CommentStatus status, Instant before, Pageable pageable);

	@org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
	@org.springframework.data.jpa.repository.Query("""
		update CommentJpaEntity c set c.pinned = false
		where c.postId = :postId and c.pinned = true
		""")
	int unpinAll(@org.springframework.data.repository.query.Param("postId") String postId);

	List<CommentJpaEntity> findByAuthorId(String authorId);

	List<CommentJpaEntity> findByAuthorIdAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(
			String authorId, CommentStatus status, Instant before, Pageable pageable);

	@org.springframework.data.jpa.repository.Query("select c.postId from CommentJpaEntity c where c.id = :id")
	java.util.Optional<String> findPostIdById(@org.springframework.data.repository.query.Param("id") String id);

	List<CommentJpaEntity> findByPostIdIn(Collection<String> postIds);

	/**
	 * Comentários que citam o termo E trazem URL de GIF — a busca de GIF da comunidade
	 * (sem provedor externo, usa o que a rede mesma já compartilhou).
	 */
	@org.springframework.data.jpa.repository.Query("""
		select c.content from CommentJpaEntity c
		where lower(c.content) like lower(:term) escape '!'
		  and (lower(c.content) like '%.gif%'
		       or lower(c.content) like '%media.giphy.com%'
		       or lower(c.content) like '%c.tenor.com%'
		       or lower(c.content) like '%media.tenor.com%')
		order by c.createdAt desc
		""")
	List<String> searchGifContents(@org.springframework.data.repository.query.Param("term") String term,
			Pageable pageable);
}
