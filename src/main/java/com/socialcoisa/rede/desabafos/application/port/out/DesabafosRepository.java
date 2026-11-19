package com.socialcoisa.rede.desabafos.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.socialcoisa.rede.desabafos.domain.DesabafoComment;
import com.socialcoisa.rede.desabafos.domain.DesabafoPost;

public interface DesabafosRepository {

	DesabafoPost savePost(DesabafoPost post);

	Optional<DesabafoPost> findPostById(String postId);

	/** O feed: desabafos no ar, do mais recente ao mais antigo, página por marca de tempo. */
	List<DesabafoPost> findFeedBefore(Instant before, int limit);

	DesabafoComment saveComment(DesabafoComment comment);

	Optional<DesabafoComment> findCommentById(String commentId);

	/** Os comentários no ar do desabafo, do mais antigo ao mais novo. */
	List<DesabafoComment> findActiveCommentsByPost(String postId);

	/** Reagir é trocar: a chave (post, pessoa) é o próprio id da linha. */
	void react(String postId, String userId, String reaction);

	void removeReaction(String postId, String userId);

	/** Totais por tipo de reação — contagem, nunca a lista de quem reagiu. */
	Map<String, Map<String, Long>> countReactionsByPostIds(List<String> postIds);

	/** A reação de quem lê, por desabafo (post → reação); sem isso o botão não acende. */
	Map<String, String> findUserReactions(String userId, Set<String> postIds);

	Map<String, Long> countActiveCommentsByPostIds(List<String> postIds);
}
