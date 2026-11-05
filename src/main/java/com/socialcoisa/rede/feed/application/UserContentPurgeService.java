package com.socialcoisa.rede.feed.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.adapters.out.persistence.UserContentPurgeRepository;
import com.socialcoisa.rede.feed.application.port.in.PurgeAuthorContentUseCase;
import com.socialcoisa.rede.feed.application.port.out.AuditLogRepository;
import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.application.port.out.FeedCacheRepository;
import com.socialcoisa.rede.feed.application.port.out.OutboxEventRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.in.RequestPostMediaDeletionUseCase;

/**
 * Purga TOTAL do conteudo de um autor, apagando LINHAS do banco (diferente da exclusao normal,
 * que e soft delete com deletedAt). Destino: contas banidas por spam.
 *
 * <p>Ordem obrigatoria de exclusao (FKs apontam para posts; comments tem FK para si mesma):
 * solta as autorreferencias de comentarios, apaga reacoes de comentario, apaga comentarios,
 * apaga as tabelas de interacao do post e por fim os posts.
 */
@Service
public class UserContentPurgeService implements PurgeAuthorContentUseCase {

	/** Teto do IN nativo: grande o bastante para um spammer, pequeno para nao estourar o pacote. */
	private static final int CHUNK = 1000;

	private final PostRepository posts;
	private final CommentRepository comments;
	private final UserContentPurgeRepository purge;
	private final FeedCacheRepository feedCache;
	private final RequestPostMediaDeletionUseCase mediaDeletion;
	private final OutboxEventRepository outbox;
	private final AuditLogRepository auditLog;

	public UserContentPurgeService(PostRepository posts, CommentRepository comments,
			UserContentPurgeRepository purge, FeedCacheRepository feedCache,
			RequestPostMediaDeletionUseCase mediaDeletion, OutboxEventRepository outbox,
			AuditLogRepository auditLog) {
		this.posts = posts;
		this.comments = comments;
		this.purge = purge;
		this.feedCache = feedCache;
		this.mediaDeletion = mediaDeletion;
		this.outbox = outbox;
		this.auditLog = auditLog;
	}

	@Override
	@Transactional
	public PurgeResult purgeAllByAuthor(String actorId, String authorId, String reason) {
		List<Post> authorPosts = posts.findAllByAuthor(new UserId(authorId));
		List<String> postIds = authorPosts.stream().map(post -> post.id().value()).toList();

		for (Post post : authorPosts) {
			feedCache.removeEverywhere(post.id());
			if (!post.mediaIds().isEmpty()) {
				mediaDeletion.requestPostMediaDeletion(new UserId(authorId), post.id(), post.mediaIds());
			}
			outbox.append("PostDeleted", post.id().value(), "{\"postId\":\"" + post.id().value()
				+ "\",\"reason\":\"USER_PURGED\"}");
		}

		Set<String> commentIds = new LinkedHashSet<>();
		comments.findAllByAuthor(authorId).forEach(comment -> commentIds.add(comment.id()));
		comments.findAllByPostIds(postIds).forEach(comment -> commentIds.add(comment.id()));
		List<String> allCommentIds = new ArrayList<>(commentIds);

		int otherRows = 0;
		if (!allCommentIds.isEmpty()) {
			// Respostas de OUTRAS pessoas a comentarios apagados viram comentario solto (toplevel).
			for (List<String> chunk : chunks(allCommentIds)) {
				otherRows += purge.detachCommentChildren(chunk);
			}
			for (List<String> chunk : chunks(allCommentIds)) {
				otherRows += purge.deleteCommentReactionsByCommentIds(chunk);
			}
			// Duvidas alheias cuja resposta aceita era do spammer voltam a ficar abertas.
			for (List<String> chunk : chunks(allCommentIds)) {
				otherRows += purge.clearAcceptedAnswers(chunk);
			}
		}
		int commentCount = 0;
		for (List<String> chunk : chunks(postIds)) {
			commentCount += purge.deleteCommentsByPostIds(chunk);
		}
		commentCount += purge.deleteCommentsByAuthor(authorId);

		for (List<String> chunk : chunks(postIds)) {
			otherRows += purge.deleteReactionsByPostIds(chunk);
			otherRows += purge.deleteSavedPostsByPostIds(chunk);
			otherRows += purge.deletePostSharesByPostIds(chunk);
			otherRows += purge.deletePostTagsByPostIds(chunk);
			otherRows += purge.deletePostMediaByPostIds(chunk);
			otherRows += purge.deletePostSealsByPostIds(chunk);
			otherRows += purge.deleteArticlesByPostIds(chunk);
			otherRows += purge.deletePostNotesByPostIds(chunk);
		}
		int postCount = 0;
		for (List<String> chunk : chunks(postIds)) {
			postCount += purge.deletePostsByIds(chunk);
		}
		otherRows += purge.deletePostNotesByAuthor(authorId);
		// Rede de seguranca: posts criados depois do SELECT (spammer ativo durante a purga).
		postCount += purge.deletePostsByAuthor(authorId);

		auditLog.record(new UserId(actorId == null || actorId.isBlank() ? authorId : actorId),
			"USER_CONTENT_PURGED", authorId,
			(reason == null ? "" : reason) + " | posts=" + postCount + " comments=" + commentCount);
		return new PurgeResult(postCount, commentCount, otherRows);
	}

	private List<List<String>> chunks(List<String> ids) {
		List<List<String>> out = new ArrayList<>();
		for (int i = 0; i < ids.size(); i += CHUNK) {
			out.add(ids.subList(i, Math.min(i + CHUNK, ids.size())));
		}
		return out;
	}
}
