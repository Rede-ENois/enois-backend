package com.socialcoisa.rede.feed.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.socialcoisa.rede.feed.domain.Comment;
import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.profile.application.ContributorTagView;
import com.socialcoisa.rede.profile.application.NameStyleView;

public record CommentView(String id, String postId, String authorId, String authorName, String authorUsername,
		String authorAvatar, String authorBadge, List<String> authorBadges, String parentCommentId, String content,
		List<String> mediaIds, Instant createdAt, Instant editedAt, ReactionType currentUserReaction,
		String authorAvatarFrame, int reactionCount, boolean pinned, boolean mediaSpoiler,
		/** Personalizacao do nome do autor (tema/fonte/cor); nulo = padrao. */
		NameStyleView authorStyle,
		/** Tag "Contribuinte" quando o autor tem selo ativo; nulo = nao e contribuinte. */
		ContributorTagView authorContributor,
		/** Contagem por tipo de reacao — e o que desenha as pilulas (igual ao post). Vazio = nenhuma. */
		Map<ReactionType, Long> reactionSummary,
		/** This comment is the accepted answer of its question. */
		boolean accepted) {

	public static CommentView from(Comment comment) {
		return new CommentView(comment.id(), comment.postId().value(), comment.authorId().value(),
			null, null, null, null, List.of(), comment.parentCommentId(), comment.content(), comment.mediaIds(),
			comment.createdAt(), comment.editedAt(), null, null, 0, comment.pinned(), comment.mediaSpoiler(), null,
			null, Map.of(), false);
	}

	public static CommentView from(Comment comment, String authorName, String authorUsername, String authorAvatar,
			List<String> authorBadges) {
		List<String> badges = authorBadges == null ? List.of() : List.copyOf(authorBadges);
		return new CommentView(comment.id(), comment.postId().value(), comment.authorId().value(),
			authorName, authorUsername, authorAvatar, badges.isEmpty() ? null : badges.get(0), badges,
			comment.parentCommentId(), comment.content(), comment.mediaIds(), comment.createdAt(),
			comment.editedAt(), null, null, 0, comment.pinned(), comment.mediaSpoiler(), null, null, Map.of(), false);
	}

	public CommentView withAvatarFrame(String frame) {
		return new CommentView(id, postId, authorId, authorName, authorUsername, authorAvatar, authorBadge,
			authorBadges, parentCommentId, content, mediaIds, createdAt, editedAt, currentUserReaction, frame,
			reactionCount, pinned, mediaSpoiler, authorStyle, authorContributor, reactionSummary, accepted);
	}

	public CommentView withCurrentUserReaction(ReactionType reaction) {
		return new CommentView(id, postId, authorId, authorName, authorUsername, authorAvatar, authorBadge,
			authorBadges, parentCommentId, content, mediaIds, createdAt, editedAt, reaction, authorAvatarFrame,
			reactionCount, pinned, mediaSpoiler, authorStyle, authorContributor, reactionSummary, accepted);
	}

	/** Anexa a personalizacao do nome do autor (cosmetico) depois de carregar o perfil. */
	public CommentView withAuthorStyle(NameStyleView style) {
		return new CommentView(id, postId, authorId, authorName, authorUsername, authorAvatar, authorBadge,
			authorBadges, parentCommentId, content, mediaIds, createdAt, editedAt, currentUserReaction,
			authorAvatarFrame, reactionCount, pinned, mediaSpoiler, style, authorContributor, reactionSummary, accepted);
	}

	/** Anexa a tag de contribuinte do autor (cosmetica) depois de conferir os selos ativos. */
	public CommentView withContributor(ContributorTagView tag) {
		return new CommentView(id, postId, authorId, authorName, authorUsername, authorAvatar, authorBadge,
			authorBadges, parentCommentId, content, mediaIds, createdAt, editedAt, currentUserReaction,
			authorAvatarFrame, reactionCount, pinned, mediaSpoiler, authorStyle, tag, reactionSummary, accepted);
	}

	public CommentView withReactionCount(int count) {
		return new CommentView(id, postId, authorId, authorName, authorUsername, authorAvatar, authorBadge,
			authorBadges, parentCommentId, content, mediaIds, createdAt, editedAt, currentUserReaction,
			authorAvatarFrame, count, pinned, mediaSpoiler, authorStyle, authorContributor, reactionSummary, accepted);
	}

	/** Anexa a contagem por tipo (pilulas de reacao do comentario, como no post). */
	public CommentView withReactionSummary(Map<ReactionType, Long> summary) {
		return new CommentView(id, postId, authorId, authorName, authorUsername, authorAvatar, authorBadge,
			authorBadges, parentCommentId, content, mediaIds, createdAt, editedAt, currentUserReaction,
			authorAvatarFrame, reactionCount, pinned, mediaSpoiler, authorStyle, authorContributor, summary, accepted);
	}

	public CommentView withAccepted(boolean value) {
		return new CommentView(id, postId, authorId, authorName, authorUsername, authorAvatar, authorBadge,
			authorBadges, parentCommentId, content, mediaIds, createdAt, editedAt, currentUserReaction,
			authorAvatarFrame, reactionCount, pinned, mediaSpoiler, authorStyle, authorContributor, reactionSummary,
			value);
	}
}
