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

	/** Posts ativos com link no corpo ou na mini página (a varredura da barreira de links). */
	@Query("""
		select p from PostJpaEntity p
		where p.status = :status and p.createdAt >= :since
		  and (p.content like '%http%' or p.pageHtml like '%http%')
		order by p.createdAt desc
		""")
	List<PostJpaEntity> findWithLinksSince(@Param("status") PostStatus status, @Param("since") Instant since,
			Pageable pageable);

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
	 * Aba "Ainda nao vi". O filtro "o leitor nao reagiu nem comentou" usa LEFT JOIN + IS NULL, e nao
	 * {@code NOT EXISTS}.
	 *
	 * <p>A forma anterior era um {@code NOT EXISTS} correlacionado. O MariaDB o reescreve como uma
	 * subconsulta MATERIALIZADA com TODO o historico do leitor: com 20 mil reacoes e 10 mil
	 * comentarios (usuario engajado), a consulta lia a chave inteira e cruzava com os candidatos —
	 * 30.096 leituras de indice e 87&nbsp;ms para devolver 20 linhas, e o custo crescia linear com o
	 * engajamento da pessoa, nao com o tamanho da pagina.</p>
	 *
	 * <p>Com o anti-join explicito o plano vira {@code Not exists} por chave: o unique
	 * {@code (post_id, user_id)} de {@code reactions} e o indice {@code (author_id, post_id)} de
	 * {@code comments} resolvem cada post candidato com UMA linha. Medido no mesmo acervo:
	 * 0,65&nbsp;ms e 2 linhas por candidato, independentemente de quanto a pessoa ja interagiu.</p>
	 *
	 * <p>O conjunto devolvido e o mesmo: LEFT JOIN com IS NULL sobre uma chave unica equivale a
	 * "nao existe linha com esta chave". {@code ReactionJpaEntity} tem unique
	 * {@code (post_id, user_id)}, entao o join nao multiplica linhas.</p>
	 */
	@Query("""
			SELECT p FROM PostJpaEntity p
			LEFT JOIN ReactionJpaEntity r ON r.postId = p.id AND r.userId = :reader
			LEFT JOIN CommentJpaEntity c ON c.postId = p.id AND c.authorId = :reader
			WHERE p.status = :status AND p.type NOT IN :excluded AND p.channelId IS NULL
			  AND p.authorId <> :reader AND p.createdAt < :before
			  AND (p.topic IS NULL OR p.topic NOT IN :blocked)
			  AND r.id IS NULL AND c.id IS NULL
			ORDER BY p.createdAt DESC""")
	List<PostJpaEntity> findUnseen(@Param("status") PostStatus status,
			@Param("excluded") Collection<PostType> excluded, @Param("reader") String reader,
			@Param("blocked") Collection<String> blocked, @Param("before") Instant before, Pageable pageable);

	@Query("""
			SELECT p FROM PostJpaEntity p
			WHERE p.status = :status AND p.createdAt < :before
			  AND (EXISTS (SELECT r.id FROM ReactionJpaEntity r WHERE r.postId = p.id AND r.userId = :reader)
			    OR EXISTS (SELECT c.id FROM CommentJpaEntity c WHERE c.postId = p.id AND c.authorId = :reader))
			ORDER BY p.createdAt DESC""")
	List<PostJpaEntity> findInteracted(@Param("status") PostStatus status, @Param("reader") String reader,
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
			  AND p.createdAt >= :since AND p.createdAt <= :until
			  AND (:afterReactions IS NULL OR p.reactions < :afterReactions
			    OR (p.reactions = :afterReactions AND (p.createdAt < :afterCreatedAt
			      OR (p.createdAt = :afterCreatedAt AND p.id < :afterId))))
			ORDER BY p.reactions DESC, p.createdAt DESC, p.id DESC""")
	List<PostJpaEntity> findTrending(@Param("status") PostStatus status,
			@Param("excluded") Collection<PostType> excluded, @Param("since") Instant since,
			@Param("until") Instant until, @Param("afterReactions") Integer afterReactions,
			@Param("afterCreatedAt") Instant afterCreatedAt, @Param("afterId") String afterId, Pageable pageable);

	/** Feed "Tudo" filtrado por tipos de post; `resolved` (nulo = tanto faz) filtra as dúvidas resolvidas. */
	@Query("""
			SELECT p FROM PostJpaEntity p
			WHERE p.status = :status AND p.type IN :types AND p.channelId IS NULL
			  AND p.createdAt < :before
			  AND (:resolved IS NULL OR (:resolved = TRUE AND p.resolvedAt IS NOT NULL)
			    OR (:resolved = FALSE AND p.resolvedAt IS NULL))
			ORDER BY p.createdAt DESC""")
	List<PostJpaEntity> findByTypes(@Param("status") PostStatus status,
			@Param("types") Collection<PostType> types, @Param("resolved") Boolean resolved,
			@Param("before") Instant before, Pageable pageable);

	/** Resposta aceita da dúvida: só esta escrita toca as duas colunas (o save() não as grava). */
	@org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("UPDATE PostJpaEntity p SET p.acceptedCommentId = :commentId, p.resolvedAt = :resolvedAt WHERE p.id = :id")
	int updateAcceptedAnswer(@Param("id") String id, @Param("commentId") String commentId,
			@Param("resolvedAt") Instant resolvedAt);

	/**
	 * The accepted answer is gone (deleted by its author or by moderation): the question is open again.
	 * Scoped to the comment's post by primary key: `accepted_comment_id` has no index, and matching
	 * on it alone scanned (and locked) every row of `posts` on each comment deletion.
	 */
	@org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("UPDATE PostJpaEntity p SET p.acceptedCommentId = NULL, p.resolvedAt = NULL"
		+ " WHERE p.id = :postId AND p.acceptedCommentId = :commentId")
	int clearAcceptedAnswer(@Param("postId") String postId, @Param("commentId") String commentId);

	/** Row lock on the post for the rest of the transaction (see PostRepository#lockForUpdate). */
	@Query(value = "select p.status from posts p where p.id = :id for update", nativeQuery = true)
	java.util.Optional<String> lockStatusForUpdate(@Param("id") String id);

	/** One statement, relative to the stored value: concurrent deltas add up instead of overwriting. */
	@org.springframework.data.jpa.repository.Modifying
	@Query("""
		UPDATE PostJpaEntity p SET
		  p.reactions = greatest(p.reactions + :reactions, 0),
		  p.comments = greatest(p.comments + :comments, 0),
		  p.saves = greatest(p.saves + :saves, 0),
		  p.shares = greatest(p.shares + :shares, 0),
		  p.reposts = greatest(p.reposts + :reposts, 0)
		WHERE p.id = :id""")
	int addToCounters(@Param("id") String id, @Param("reactions") int reactions, @Param("comments") int comments,
			@Param("saves") int saves, @Param("shares") int shares, @Param("reposts") int reposts);

	@Query("""
		SELECT new com.socialcoisa.rede.feed.domain.Counters(p.reactions, p.comments, p.saves, p.shares, p.reposts)
		FROM PostJpaEntity p WHERE p.id = :id""")
	java.util.Optional<com.socialcoisa.rede.feed.domain.Counters> findCounters(@Param("id") String id);

	/** Todos os posts do autor (sem filtro de status) — ban cleanup, PR #37. */
}
