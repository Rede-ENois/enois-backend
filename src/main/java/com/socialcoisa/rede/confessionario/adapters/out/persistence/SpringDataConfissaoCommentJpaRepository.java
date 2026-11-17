package com.socialcoisa.rede.confessionario.adapters.out.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.confessionario.domain.ConfissaoStatus;

interface SpringDataConfissaoCommentJpaRepository extends JpaRepository<ConfissaoCommentJpaEntity, String> {

	List<ConfissaoCommentJpaEntity> findByPostIdAndStatusOrderByCreatedAtAsc(String postId, ConfissaoStatus status);

	@Query("""
		select c.postId as postId, count(c.id) as total
		from ConfissaoCommentJpaEntity c
		where c.postId in :postIds and c.status = :status
		group by c.postId
		""")
	List<CommentCountProjection> countByPostIdIn(@Param("postIds") Collection<String> postIds,
		@Param("status") ConfissaoStatus status);

	interface CommentCountProjection {

		String getPostId();

		long getTotal();
	}
}
