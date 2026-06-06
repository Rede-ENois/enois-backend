package com.socialcoisa.rede.feed.application;

import java.time.Instant;
import java.util.List;

import com.socialcoisa.rede.feed.domain.Counters;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostType;

public record PostView(String id, String authorId, String authorUsername, String authorName, String authorAvatar,
		PostType type, String content, List<String> tags, List<String> mediaIds, Instant createdAt, Counters counters) {

	public static PostView from(Post post) {
		return new PostView(post.id().value(), post.authorId().value(), null, null, null, post.type(), post.content(),
			post.tags(), post.mediaIds(), post.createdAt(), post.counters());
	}

	public static PostView from(Post post, String authorUsername, String authorName, String authorAvatar) {
		return new PostView(post.id().value(), post.authorId().value(), authorUsername, authorName, authorAvatar,
			post.type(), post.content(), post.tags(), post.mediaIds(), post.createdAt(), post.counters());
	}
}
