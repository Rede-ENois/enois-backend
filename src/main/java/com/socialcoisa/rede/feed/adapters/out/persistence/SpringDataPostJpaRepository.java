package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.feed.domain.AuthorIdentity;
import com.socialcoisa.rede.feed.domain.PostStatus;
import com.socialcoisa.rede.feed.domain.PostType;

interface SpringDataPostJpaRepository extends JpaRepository<PostJpaEntity, String> {

	List<PostJpaEntity> findByAuthorId(String authorId);

	@Query("""
		select p from PostJpaEntity p
		where p.authorId = :authorId and p.repostOf = :source and p.status = :status
		""")
	java.util.Optional<PostJpaEntity> findRepostByAuthorAndSource(@Param("authorId") String authorId,
			@Param("source") String source, @Param("status") com.socialcoisa.rede.feed.domain.PostStatus status);

	@Query("""
		select p from PostJpaEntity p
		where p.repostOf = :source and p.status = :status
		""")
	List<PostJpaEntity> findRepostsBySource(@Param("source") String source,
			@Param("status") com.socialcoisa.rede.feed.domain.PostStatus status);

	List<PostJpaEntity> findByIdIn(Collection<String> ids);

	/** Posts que anexam a mídia — a checagem de conteúdo derruba tudo o que usa a mesma imagem. */
	@Query("select p from PostJpaEntity p where :mediaId member of p.mediaIds")
	List<PostJpaEntity> findByMediaId(@Param("mediaId") String mediaId);

	List<PostJpaEntity> findByAuthorIdInAndStatusAndChannelIdIsNullAndCreatedAtBeforeOrderByCreatedAtDesc(Collection<String> authorIds,
			PostStatus status, Instant before, Pageable pageable);

	List<PostJpaEntity> findByAuthorIdAndStatusAndTypeNotAndChannelIdIsNullAndCreatedAtBeforeOrderByCreatedAtDesc(String authorId,
			PostStatus status, PostType excludedType, Instant before, Pageable pageable);

	List<PostJpaEntity> findByCommunityIdAndStatusAndChannelIdIsNullAndCreatedAtBeforeOrderByCreatedAtDesc(String communityId,
			PostStatus status, Instant before, Pageable pageable);

	List<PostJpaEntity> findByStatusAndTypeNotAndChannelIdIsNullAndCreatedAtBeforeOrderByCreatedAtDesc(PostStatus status,
			PostType excludedType, Instant before, Pageable pageable);

	List<PostJpaEntity> findByTypeAndStatusAndChannelIdIsNullAndCreatedAtBeforeOrderByCreatedAtDesc(PostType type, PostStatus status,
			Instant before, Pageable pageable);

	/** Posts de um canal privado (só o feed do canal usa; nunca os feeds gerais). */
	List<PostJpaEntity> findByChannelIdAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(String channelId,
			PostStatus status, Instant before, Pageable pageable);

	/** Momentos vencidos e ainda ativos — o worker aplica o soft-delete do fluxo normal. */
	List<PostJpaEntity> findByTypeAndStatusAndExpiresAtBefore(PostType type, PostStatus status, Instant now);

	// ---- Separação de identidades (perfil profissional): regras no backend, sempre ----

	/**
	 * Feed "Tudo": exclui BLOG (o conteúdo mora no blog) e BUSINESS (assina o feed corporativo).
	 * Uma única consulta — nada de buscar tudo e filtrar fora.
	 */
	@Query("""
			SELECT p FROM PostJpaEntity p
			WHERE p.status = :status AND p.type NOT IN :excluded AND p.channelId IS NULL
			  AND p.createdAt < :before
			ORDER BY p.createdAt DESC""")
	List<PostJpaEntity> findFeedExcluding(@Param("status") PostStatus status,
			@Param("excluded") Collection<PostType> excluded, @Param("before") Instant before, Pageable pageable);

	/**
	 * Posts de um conjunto de autores (Para você / Seguindo): só o que a identidade SOCIAL
	 * assinou — o conteúdo profissional existe apenas no feed corporativo.
	 */
	@Query("""
			SELECT p FROM PostJpaEntity p
			WHERE p.authorId IN :authorIds AND p.status = :status
			  AND p.authorIdentity = :social AND p.channelId IS NULL AND p.createdAt < :before
			ORDER BY p.createdAt DESC""")
	List<PostJpaEntity> findSocialByAuthorIds(@Param("authorIds") Collection<String> authorIds,
			@Param("status") PostStatus status, @Param("social") AuthorIdentity social,
			@Param("before") Instant before, Pageable pageable);

	/**
	 * Perfil SOCIAL do autor: só o que a identidade social assinou, sem BLOG (o conteúdo mora
	 * no blog) e sem o que a identidade profissional assinou (isso é do perfil profissional).
	 */
	@Query("""
			SELECT p FROM PostJpaEntity p
			WHERE p.authorId = :authorId AND p.status = :status
			  AND p.authorIdentity = :social AND p.type <> :blog AND p.channelId IS NULL
			  AND p.createdAt < :before
			ORDER BY p.createdAt DESC""")
	List<PostJpaEntity> findSocialByAuthor(@Param("authorId") String authorId, @Param("status") PostStatus status,
			@Param("social") AuthorIdentity social, @Param("blog") PostType blog, @Param("before") Instant before,
			Pageable pageable);

	/**
	 * Total do que o perfil SOCIAL do autor lista: mesmos filtros da consulta acima, sem o
	 * ponteiro de cursor. O card de publicações precisa do inteiro, não do que coube na página.
	 */
	@Query("""
			SELECT COUNT(p) FROM PostJpaEntity p
			WHERE p.authorId = :authorId AND p.status = :status
			  AND p.authorIdentity = :social AND p.type <> :blog AND p.channelId IS NULL""")
	long countSocialByAuthor(@Param("authorId") String authorId, @Param("status") PostStatus status,
			@Param("social") AuthorIdentity social, @Param("blog") PostType blog);

	/**
	 * Feed "Tudo" com os assuntos bloqueados do leitor: posts SEM tópico nunca são bloqueados
	 * (sem classificação não há evidência — regra do plano).
	 */
	@Query("""
			SELECT p FROM PostJpaEntity p
			WHERE p.status = :status AND p.type NOT IN :excluded AND p.channelId IS NULL
			  AND p.createdAt < :before AND (p.topic IS NULL OR p.topic NOT IN :blocked)
			ORDER BY p.createdAt DESC""")
	List<PostJpaEntity> findFeedExcludingAndBlocking(@Param("status") PostStatus status,
			@Param("excluded") Collection<PostType> excluded, @Param("blocked") Collection<String> blocked,
			@Param("before") Instant before, Pageable pageable);

	/**
	 * "Para você"/Seguindo com assuntos bloqueados: apenas o que a identidade SOCIAL assinou
	 * e cujo tópico não está bloqueado (posts sem tópico passam).
	 */
	@Query("""
			SELECT p FROM PostJpaEntity p
			WHERE p.authorId IN :authorIds AND p.status = :status
			  AND p.authorIdentity = :social AND p.channelId IS NULL AND p.createdAt < :before
			  AND (p.topic IS NULL OR p.topic NOT IN :blocked)
			ORDER BY p.createdAt DESC""")
	List<PostJpaEntity> findSocialByAuthorIdsAndBlocking(@Param("authorIds") Collection<String> authorIds,
			@Param("status") PostStatus status, @Param("social") AuthorIdentity social,
			@Param("blocked") Collection<String> blocked, @Param("before") Instant before, Pageable pageable);

	/** Feed corporativo: só o que foi assinado pela identidade profissional. */
	@Query("""
			SELECT p FROM PostJpaEntity p
			WHERE p.status = :status AND p.authorIdentity = :corporate AND p.channelId IS NULL
			  AND p.createdAt < :before
			ORDER BY p.createdAt DESC""")
	List<PostJpaEntity> findCorporateByStatus(@Param("status") PostStatus status,
			@Param("corporate") AuthorIdentity corporate, @Param("before") Instant before, Pageable pageable);

	/** Página do perfil profissional: só o que o dono assinou como CORPORATE. */
	@Query("""
			SELECT p FROM PostJpaEntity p
			WHERE p.authorId = :authorId AND p.status = :status
			  AND p.authorIdentity = :corporate AND p.channelId IS NULL AND p.createdAt < :before
			ORDER BY p.createdAt DESC""")
	List<PostJpaEntity> findCorporateByAuthor(@Param("authorId") String authorId, @Param("status") PostStatus status,
			@Param("corporate") AuthorIdentity corporate, @Param("before") Instant before, Pageable pageable);
	/** Em alta: mais reagidos numa janela de tempo, ordenados por reações desc. */
	@Query("""
			SELECT p FROM PostJpaEntity p
			WHERE p.status = :status AND p.type NOT IN :excluded AND p.channelId IS NULL
			  AND p.createdAt >= :since
			ORDER BY p.reactions DESC, p.createdAt DESC""")
	List<PostJpaEntity> findTrending(@Param("status") PostStatus status,
			@Param("excluded") Collection<PostType> excluded, @Param("since") Instant since, Pageable pageable);

	/** Feed "Tudo" filtrado por tipos de post. */
	@Query("""
			SELECT p FROM PostJpaEntity p
			WHERE p.status = :status AND p.type IN :types AND p.channelId IS NULL
			  AND p.createdAt < :before
			ORDER BY p.createdAt DESC""")
	List<PostJpaEntity> findByTypes(@Param("status") PostStatus status,
			@Param("types") Collection<PostType> types, @Param("before") Instant before, Pageable pageable);

	/** Todos os posts do autor (sem filtro de status) — ban cleanup, PR #37. */
}
