package com.socialcoisa.rede.confessionario.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
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

	@Query("""
		select c from ConfissaoCommentJpaEntity c
		where c.status = :status and c.createdAt >= :de and c.createdAt < :ate
		order by c.createdAt asc, c.id asc
		""")
	List<ConfissaoCommentJpaEntity> findAtivosEntre(@Param("status") ConfissaoStatus status, @Param("de") Instant de,
		@Param("ate") Instant ate, Pageable pageable);

	/**
	 * A mesma janela, por chave (a leitura do Termômetro): de `inicio` (inclusivo) em diante
	 * quando `depois` está vazio — o começo da janela — e, no instante do cursor, só os ids a
	 * partir de `depois` (a página seguinte).
	 */
	@Query("""
		select c from ConfissaoCommentJpaEntity c
		where c.status = :status and c.createdAt < :ate
		  and (c.createdAt > :inicio or (c.createdAt = :inicio and c.id >= :depois))
		order by c.createdAt asc, c.id asc
		""")
	List<ConfissaoCommentJpaEntity> findAtivosDaChave(@Param("status") ConfissaoStatus status,
		@Param("inicio") Instant inicio, @Param("depois") String depois, @Param("ate") Instant ate,
		Pageable pageable);

	interface CommentCountProjection {

		String getPostId();

		long getTotal();
	}
}
