package com.socialcoisa.rede.feed.domain;

import java.time.Instant;

public record Comment(String id, PostId postId, UserId authorId, String parentCommentId, String content,
		Instant createdAt, CommentStatus status) {

	public static Comment create(String id, PostId postId, UserId authorId, String parentCommentId, String content,
			Instant createdAt) {
		return new Comment(id, postId, authorId, parentCommentId, content, createdAt, CommentStatus.ACTIVE);
	}
}
