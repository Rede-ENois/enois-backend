package com.socialcoisa.rede.feed.application;

import java.time.Instant;
import java.util.List;

import com.socialcoisa.rede.feed.domain.Counters;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.ReactionType;

public record PostView(String id, String authorId, String authorUsername, String authorName, String authorAvatar,
		PostType type, String content, List<String> tags, List<String> mediaIds, Instant createdAt, Counters counters,
		ReactionType currentUserReaction) {

	public static PostView from(Post post) {
		return new PostView(post.id().value(), post.authorId().value(), null, null, null, post.type(), post.content(),
			post.tags(), post.mediaIds(), post.createdAt(), post.counters(), null);
	}

	public static PostView from(Post post, String authorUsername, String authorName, String authorAvatar) {
		return new PostView(post.id().value(), post.authorId().value(), authorUsername, authorName, authorAvatar,
			post.type(), post.content(), post.tags(), post.mediaIds(), post.createdAt(), post.counters(), null);
	}

	public PostView withCurrentUserReaction(ReactionType reaction) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, type, content, tags, mediaIds,
			createdAt, counters, reaction);
	}
}
