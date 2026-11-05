package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.util.List;

import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Deletes fisicos do conteudo de um autor. Query nativa porque o derived delete do JPA carrega as
 * entidades para memoria antes de apagar — inaceitavel para um spammer com milhares de linhas.
 *
 * <p>A maioria destas tabelas nao tem FK de proposito (licao de collation da V33/V46/V52/V58), e a
 * que tem (comments/reactions/saved_posts/post_shares/post_tags/post_media → posts) exige a ordem
 * de exclusao que o servico de purga garante.
 */
public interface UserContentPurgeRepository extends Repository<PostJpaEntity, String> {

	@Modifying
	@Query(value = "delete from comment_reactions where comment_id in (:commentIds)", nativeQuery = true)
	int deleteCommentReactionsByCommentIds(@Param("commentIds") List<String> commentIds);

	@Modifying
	@Query(value = "update comments set parent_comment_id = null where parent_comment_id in (:commentIds)",
		nativeQuery = true)
	int detachCommentChildren(@Param("commentIds") List<String> commentIds);

	@Modifying
	@Query(value = "update posts set accepted_comment_id = null, resolved_at = null"
		+ " where accepted_comment_id in (:commentIds)", nativeQuery = true)
	int clearAcceptedAnswers(@Param("commentIds") List<String> commentIds);

	@Modifying
	@Query(value = "delete from comments where author_id = :authorId", nativeQuery = true)
	int deleteCommentsByAuthor(@Param("authorId") String authorId);

	@Modifying
	@Query(value = "delete from comments where post_id in (:postIds)", nativeQuery = true)
	int deleteCommentsByPostIds(@Param("postIds") List<String> postIds);

	@Modifying
	@Query(value = "delete from reactions where post_id in (:postIds)", nativeQuery = true)
	int deleteReactionsByPostIds(@Param("postIds") List<String> postIds);

	@Modifying
	@Query(value = "delete from saved_posts where post_id in (:postIds)", nativeQuery = true)
	int deleteSavedPostsByPostIds(@Param("postIds") List<String> postIds);

	@Modifying
	@Query(value = "delete from post_shares where post_id in (:postIds)", nativeQuery = true)
	int deletePostSharesByPostIds(@Param("postIds") List<String> postIds);

	@Modifying
	@Query(value = "delete from post_tags where post_id in (:postIds)", nativeQuery = true)
	int deletePostTagsByPostIds(@Param("postIds") List<String> postIds);

	@Modifying
	@Query(value = "delete from post_media where post_id in (:postIds)", nativeQuery = true)
	int deletePostMediaByPostIds(@Param("postIds") List<String> postIds);

	@Modifying
	@Query(value = "delete from post_seals where post_id in (:postIds)", nativeQuery = true)
	int deletePostSealsByPostIds(@Param("postIds") List<String> postIds);

	@Modifying
	@Query(value = "delete from articles where post_id in (:postIds)", nativeQuery = true)
	int deleteArticlesByPostIds(@Param("postIds") List<String> postIds);

	@Modifying
	@Query(value = "delete from post_notes where post_id in (:postIds)", nativeQuery = true)
	int deletePostNotesByPostIds(@Param("postIds") List<String> postIds);

	@Modifying
	@Query(value = "delete from post_notes where author_id = :authorId", nativeQuery = true)
	int deletePostNotesByAuthor(@Param("authorId") String authorId);

	@Modifying
	@Query(value = "delete from posts where id in (:postIds)", nativeQuery = true)
	int deletePostsByIds(@Param("postIds") List<String> postIds);

	@Modifying
	@Query(value = "delete from posts where author_id = :authorId", nativeQuery = true)
	int deletePostsByAuthor(@Param("authorId") String authorId);
}
