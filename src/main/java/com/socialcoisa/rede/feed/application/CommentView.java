package com.socialcoisa.rede.feed.application;

import java.time.Instant;
import java.util.List;

import com.socialcoisa.rede.feed.domain.Comment;
import com.socialcoisa.rede.feed.domain.ReactionType;

public record CommentView(String id, String postId, String authorId, String authorName, String authorUsername,
		String authorAvatar, String authorBadge, List<String> authorBadges, String parentCommentId, String content,
		List<String> mediaIds, Instant createdAt, Instant editedAt, ReactionType currentUserReaction, String authorAvatarFrame) {

	public static CommentView from(Comment comment) {
		return new CommentView(comment.id(), comment.postId().value(), comment.authorId().value(),
			null, null, null, null, List.of(), comment.parentCommentId(), comment.content(), comment.mediaIds(),
			comment.createdAt(), comment.editedAt(), null, null);
	}

	/**
	 * `authorUsername` deixa o nome e a foto do comentario abrirem o perfil de quem comentou.
	 *
	 * `authorBadges` leva TODOS os emblemas da pessoa (o `authorBadge` continua sendo o primeiro,
	 * para uma versão antiga do app).
	 */
	public static CommentView from(Comment comment, String authorName, String authorUsername, String authorAvatar,
			List<String> authorBadges) {
		List<String> badges = authorBadges == null ? List.of() : List.copyOf(authorBadges);
		return new CommentView(comment.id(), comment.postId().value(), comment.authorId().value(),
			authorName, authorUsername, authorAvatar, badges.isEmpty() ? null : badges.get(0), badges,
			comment.parentCommentId(), comment.content(), comment.mediaIds(), comment.createdAt(),
			comment.editedAt(), null, null);
	}

	public CommentView withAvatarFrame(String frame) {
		return new CommentView(id, postId, authorId, authorName, authorUsername, authorAvatar, authorBadge,
			authorBadges, parentCommentId, content, mediaIds, createdAt, editedAt, currentUserReaction, frame);
	}

	/** Anexa a reacao do proprio leitor (o front precisa dela para nao "perder" a reacao ao recarregar). */
	public CommentView withCurrentUserReaction(ReactionType reaction) {
		return new CommentView(id, postId, authorId, authorName, authorUsername, authorAvatar, authorBadge,
			authorBadges, parentCommentId, content, mediaIds, createdAt, editedAt, reaction, authorAvatarFrame);
	}
}
