package com.socialcoisa.rede.feed.application;

import java.time.Instant;

import com.socialcoisa.rede.feed.domain.Comment;

public record CommentView(String id, String postId, String authorId, String parentCommentId, String content,
		Instant createdAt) {

	public static CommentView from(Comment comment) {
		return new CommentView(comment.id(), comment.postId().value(), comment.authorId().value(),
			comment.parentCommentId(), comment.content(), comment.createdAt());
	}
}
