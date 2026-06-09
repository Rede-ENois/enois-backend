package com.socialcoisa.rede.feed.application;

import java.util.List;

import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

public record CommentOnPostCommand(UserId authorId, PostId postId, String parentCommentId, String content,
		List<String> mentionedUserIds) {
}
