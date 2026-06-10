package com.socialcoisa.rede.feed.application;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.Comment;

public record CommentView(String id, String postId, String authorId, String authorName, String authorAvatar,
		String parentCommentId, String content, Instant createdAt) {

	public static CommentView from(Comment comment) {
		return new CommentView(comment.id(), comment.postId().value(), comment.authorId().value(),
			null, null, comment.parentCommentId(), comment.content(), comment.createdAt());
	}

	public static CommentView from(Comment comment, String authorName, String authorAvatar) {
		return new CommentView(comment.id(), comment.postId().value(), comment.authorId().value(),
			authorName, authorAvatar, comment.parentCommentId(), comment.content(), comment.createdAt());
	}
}
