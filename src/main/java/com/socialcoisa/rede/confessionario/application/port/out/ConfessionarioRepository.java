package com.socialcoisa.rede.confessionario.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.socialcoisa.rede.confessionario.domain.ConfissaoComment;
import com.socialcoisa.rede.confessionario.domain.ConfissaoPost;

public interface ConfessionarioRepository {

	ConfissaoPost savePost(ConfissaoPost post);

	Optional<ConfissaoPost> findPostById(String postId);

	/** O feed: confissões no ar, do mais recente ao mais antigo, página por marca de tempo. */
	List<ConfissaoPost> findFeedBefore(Instant before, int limit);

	ConfissaoComment saveComment(ConfissaoComment comment);

	Optional<ConfissaoComment> findCommentById(String commentId);

	/** Os comentários no ar da confissão, do mais antigo ao mais novo. */
	List<ConfissaoComment> findActiveCommentsByPost(String postId);

	/** Reagir é trocar: a chave (post, pessoa) é o próprio id da linha. */
	void react(String postId, String userId, String reaction);

	void removeReaction(String postId, String userId);

	/** Totais por tipo de reação — contagem, nunca a lista de quem reagiu. */
	Map<String, Map<String, Long>> countReactionsByPostIds(List<String> postIds);

	/** A reação de quem lê, por confissão (post → reação); sem isso o botão não acende. */
	Map<String, String> findUserReactions(String userId, Set<String> postIds);

	Map<String, Long> countActiveCommentsByPostIds(List<String> postIds);
}
