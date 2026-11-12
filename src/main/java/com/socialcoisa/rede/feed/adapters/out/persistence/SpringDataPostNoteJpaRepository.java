package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.feed.domain.PostNoteStatus;

interface SpringDataPostNoteJpaRepository extends JpaRepository<PostNoteJpaEntity, String> {

	List<PostNoteJpaEntity> findByPostIdInAndStatusOrderByCreatedAtDesc(Collection<String> postIds,
			PostNoteStatus status);

	Optional<PostNoteJpaEntity> findFirstByAuthorIdAndPostIdOrderByCreatedAtDesc(String authorId, String postId);

	List<PostNoteJpaEntity> findByAuthorIdAndPostIdIn(String authorId, Collection<String> postIds);

	List<PostNoteJpaEntity> findByPostIdAndStatusOrderByCreatedAtAsc(String postId, PostNoteStatus status);

	boolean existsByPostIdAndStatus(String postId, PostNoteStatus status);

	List<PostNoteJpaEntity> findByStatusOrderByCreatedAtAsc(PostNoteStatus status,
			org.springframework.data.domain.Pageable pageable);

	int countByAuthorIdAndStatus(String authorId, PostNoteStatus status);

	@Query("select n.authorId, count(n) from PostNoteJpaEntity n"
		+ " where n.authorId in :authorIds and n.status = :status group by n.authorId")
	List<Object[]> countByAuthorIdInAndStatus(@Param("authorIds") Collection<String> authorIds,
		@Param("status") PostNoteStatus status);
}