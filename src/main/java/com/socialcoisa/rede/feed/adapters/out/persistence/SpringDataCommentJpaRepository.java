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

	/** Comentários ativos num intervalo de criação (a janela de leitura do Termômetro). */
	@org.springframework.data.jpa.repository.Query("""
			select c from CommentJpaEntity c
			where c.status = :status and c.createdAt >= :de and c.createdAt < :ate
			order by c.createdAt asc, c.id asc
			""")
	List<CommentJpaEntity> findAtivosEntre(
			@org.springframework.data.repository.query.Param("status") CommentStatus status,
			@org.springframework.data.repository.query.Param("de") Instant de,
			@org.springframework.data.repository.query.Param("ate") Instant ate, Pageable pageable);

	/**
	 * A mesma janela, por chave (a leitura do Termômetro): de `inicio` (inclusivo) em diante
	 * quando `depois` está vazio — o começo da janela — e, no instante do cursor, só os ids a
	 * partir de `depois` (a página seguinte).
	 */
	@org.springframework.data.jpa.repository.Query("""
			select c from CommentJpaEntity c
			where c.status = :status and c.createdAt < :ate
			  and (c.createdAt > :inicio or (c.createdAt = :inicio and c.id >= :depois))
			order by c.createdAt asc, c.id asc
			""")
	List<CommentJpaEntity> findAtivosDepois(
			@org.springframework.data.repository.query.Param("status") CommentStatus status,
			@org.springframework.data.repository.query.Param("inicio") Instant inicio,
			@org.springframework.data.repository.query.Param("depois") String depois,
			@org.springframework.data.repository.query.Param("ate") Instant ate, Pageable pageable);

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
