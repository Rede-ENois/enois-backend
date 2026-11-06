package com.socialcoisa.rede.feed.domain;

/**
 * A denormalized counter of a post. Each one mirrors rows of its own table (reactions, comments,
 * saved_posts, post_shares, post_reposts) and is only changed by an atomic SQL delta.
 */
public enum PostCounter {
	REACTIONS, COMMENTS, SAVES, SHARES, REPOSTS
}
