package com.socialcoisa.rede.colagem.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.colagem.domain.ColagemComment;
import com.socialcoisa.rede.colagem.domain.ColagemPost;

/**
 * Persistência da Colagem. As contagens são consultadas em lote pela página (sem contador
 * desnormalizado: o volume do espaço é pequeno e a consulta é indexada).
 */
public interface ColagemRepository {

	ColagemPost savePost(ColagemPost post);

	Optional<ColagemPost> findPostById(String id);

	/** Feed da Colagem: ativos, mais recentes primeiro, cursor por marca de tempo. */
	List<ColagemPost> findFeedBefore(Instant before, int limit);

	/** Fotos ativas que usam a mídia — o takedown de mídia explícita passa por aqui. */
	List<ColagemPost> findActivePostsByMediaId(String mediaId);

	ColagemComment saveComment(ColagemComment comment);

	Optional<ColagemComment> findCommentById(String id);

	/** Contribuições de um post, na ordem em que chegaram (crônica ascendente). */
	List<ColagemComment> findActiveCommentsByPost(String postId);

	/** Comentários ativos que usam a mídia (takedown de mídia em comentário fotográfico). */
	List<ColagemComment> findActiveCommentsByMediaId(String mediaId);

	/** Total de contribuições ativas por post, em lote (a página inteira com uma consulta). */
	Map<String, Long> countActiveCommentsByPostIds(Collection<String> postIds);

	/** Reações por post e tipo, em lote: {postId: {LIKE: 3, FIRE: 1}}. */
	Map<String, Map<String, Long>> countReactionsByPostIds(Collection<String> postIds);

	/** Reação do leitor num conjunto de posts: {postId: FIRE}. */
	Map<String, String> findUserReactions(String userId, Collection<String> postIds);

	/** Uma reação por pessoa por foto: trocar o tipo é UPDATE, remover é DELETE. */
	void react(String postId, String userId, String reaction);

	void removeReaction(String postId, String userId);
}
