package com.socialcoisa.rede.feed.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.feed.domain.Comment;
import com.socialcoisa.rede.feed.domain.PostId;

public interface CommentRepository {

	Comment save(Comment comment);

	Optional<Comment> findById(String id);

	List<Comment> findActiveByPost(PostId postId, Instant before, int limit);

	/** Comentário fixado do post (no máximo um; o serviço garante). */
	Optional<Comment> findPinnedByPost(PostId postId);

	/** Paginação normal sem o fixado: ele é devolvido no topo da primeira página. */
	List<Comment> findActiveByPostExcludingPinned(PostId postId, Instant before, int limit);

	/** Desfixa todos os comentários do post (chamado ao fixar outro). */
	void unpinAllByPost(PostId postId);

	void deleteById(String id);

	/** Soft delete (status DELETED) for moderation: the row stays so a confirmed report keeps its target. */
	void markDeleted(Comment comment);

	List<String> findPostIdsByAuthor(String authorId);

	/** TODOS os comentários do autor, em qualquer status — purga por spam e ban cleanup. */
	List<Comment> findAllByAuthor(String authorId);

	/** Todos os comentários (qualquer status) dos posts dados — uso da purga. */
	List<Comment> findAllByPostIds(java.util.Collection<String> postIds);
}
