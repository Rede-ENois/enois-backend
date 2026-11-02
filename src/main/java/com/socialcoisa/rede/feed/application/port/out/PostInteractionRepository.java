package com.socialcoisa.rede.feed.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.feed.domain.UserId;

public interface PostInteractionRepository {

	/**
	 * Registra a reacao do usuario no post. Distinguir "nova" de "trocada" e o que impede a contagem
	 * de subir quando a pessoa so muda o tipo da propria reacao.
	 */
	ReactionOutcome addReaction(String id, PostId postId, UserId userId, ReactionType type, Instant createdAt);

	/** Resultado de registrar uma reacao: so {@link #ADDED} e {@link #REMOVED} mexem no total. */
	enum ReactionOutcome {
		/** Nao havia reacao: o total sobe. */
		ADDED,
		/** Havia reacao de outro tipo e foi trocada: o total nao muda. */
		REPLACED,
		/** A mesma reacao foi desfeita (toggle): o total desce. */
		REMOVED
	}

	boolean removeReaction(PostId postId, UserId userId);

	boolean addSave(String id, PostId postId, UserId userId, Instant createdAt);

	boolean removeSave(PostId postId, UserId userId);

	boolean addShare(String id, PostId postId, UserId userId, Instant createdAt);

	List<String> findSavedPostIdsByUserId(UserId userId);

	/** Post IDs que o usuario reagiu, do mais recente ao mais antigo (limitado). */
	List<String> findReactedPostIdsByUserId(UserId userId, int limit);

	Map<PostId, ReactionType> findReactionsByUserAndPostIds(UserId userId, Collection<PostId> postIds);

	/** Most recent reactions of a post (created before `before`, at most `limit`). */
	List<ReactionSummary> findReactionsByPostId(PostId postId, Instant before, int limit);

	/**
	 * Reaction counts per type for several posts in one query, ignoring reactions from
	 * `excludedUserIds` (people the reader blocked). Posts without reactions are absent.
	 */
	Map<PostId, Map<ReactionType, Long>> countReactionsByPostIds(Collection<PostId> postIds,
			Collection<String> excludedUserIds);

	boolean addCommentReaction(String id, String commentId, UserId userId, ReactionType type, Instant createdAt);

	/** Remove a reacao do usuario no comentario (qualquer tipo). false quando nao havia. */
	boolean removeCommentReaction(String commentId, UserId userId);

	/** Reacoes do usuario para varios comentarios (id do comentario -> tipo), em uma consulta. */
	Map<String, ReactionType> findCommentReactionsByUser(UserId userId, Collection<String> commentIds);
	List<ReactionSummary> findReactionsByCommentId(String commentId);

	/** Contagem de reacoes por comentario e por tipo (em batch, pra desenhar as pilulas sem N+1). */
	Map<String, Map<ReactionType, Long>> countCommentReactionsByType(Collection<String> commentIds,
			Collection<String> excludedUserIds);

	record ReactionSummary(String userId, ReactionType type, Instant createdAt) {
	}
}
