package com.socialcoisa.rede.colagem.adapters.out.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.colagem.domain.ColagemStatus;

interface SpringDataColagemCommentJpaRepository extends JpaRepository<ColagemCommentJpaEntity, String> {

	List<ColagemCommentJpaEntity> findByPostIdAndStatusOrderByCreatedAtAsc(String postId, ColagemStatus status);

	List<ColagemCommentJpaEntity> findByMediaIdAndStatus(String mediaId, ColagemStatus status);

	/** Contagem de contribuições ativas em lote: uma consulta responde a página inteira. */
	@Query("""
		select c.postId as postId, count(c.id) as total
		from ColagemCommentJpaEntity c
		where c.postId in :postIds and c.status = :status
		group by c.postId
		""")
	List<CommentCountProjection> countByPostIdIn(@Param("postIds") Collection<String> postIds,
		@Param("status") ColagemStatus status);

	interface CommentCountProjection {

		String getPostId();

		long getTotal();
	}
}
