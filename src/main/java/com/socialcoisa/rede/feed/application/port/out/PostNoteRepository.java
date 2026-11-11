package com.socialcoisa.rede.feed.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.feed.domain.PostNote;
import com.socialcoisa.rede.feed.domain.PostNoteStatus;

public interface PostNoteRepository {

	PostNote save(PostNote note);

	Optional<PostNote> findById(String id);

	/** Notas aprovadas de vários posts (uma consulta por página do feed). */
	List<PostNote> findApprovedByPostIds(Collection<String> postIds);

	/** Nota de um autor num post, em qualquer estado (evita duplicidade). */
	Optional<PostNote> findByAuthorAndPost(String authorId, String postId);

	/** Notas do próprio leitor nos posts da página (para mostrar "em análise" no card). */
	List<PostNote> findByAuthorAndPosts(String authorId, Collection<String> postIds);

	/** Notas ainda pendentes de um post (para descartar as perdedoras ao aprovar uma). */
	List<PostNote> findPendingByPost(String postId);

	boolean existsByPostIdAndStatus(String postId, PostNoteStatus status);

	List<PostNote> findByStatus(PostNoteStatus status, int limit);

	int countByAuthorAndStatus(String authorId, PostNoteStatus status);

	/** Notas de cada autor do conjunto num estado, em uma consulta (GROUP BY no autor). */
	Map<String, Integer> countByAuthorsAndStatus(Collection<String> authorIds, PostNoteStatus status);
}