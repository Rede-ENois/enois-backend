package com.socialcoisa.rede.desabafos.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.desabafos.domain.DesabafoStatus;

interface SpringDataDesabafoCommentJpaRepository extends JpaRepository<DesabafoCommentJpaEntity, String> {

	List<DesabafoCommentJpaEntity> findByPostIdAndStatusOrderByCreatedAtAsc(String postId, DesabafoStatus status);

	@Query("""
		select c.postId as postId, count(c.id) as total
		from DesabafoCommentJpaEntity c
		where c.postId in :postIds and c.status = :status
		group by c.postId
		""")
	List<CommentCountProjection> countByPostIdIn(@Param("postIds") Collection<String> postIds,
		@Param("status") DesabafoStatus status);

	@Query("""
		select c from DesabafoCommentJpaEntity c
		where c.status = :status and c.createdAt >= :de and c.createdAt < :ate
		order by c.createdAt asc
		""")
	List<DesabafoCommentJpaEntity> findAtivosEntre(@Param("status") DesabafoStatus status, @Param("de") Instant de,
		@Param("ate") Instant ate, Pageable pageable);

	interface CommentCountProjection {

		String getPostId();

		long getTotal();
	}
}
