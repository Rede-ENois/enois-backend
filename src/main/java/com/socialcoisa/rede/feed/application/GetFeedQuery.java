package com.socialcoisa.rede.feed.application;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import com.socialcoisa.rede.feed.domain.FeedTab;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;

/** `resolved` narrows a type-filtered feed to answered (true) or open (false) questions; null = both. */
public record GetFeedQuery(UserId userId, Optional<Instant> before, int limit, FeedTab tab, Set<PostType> types,
		Boolean resolved, TrendingCursor trendingCursor) {

	public GetFeedQuery(UserId userId, Optional<Instant> before, int limit, FeedTab tab, Set<PostType> types,
			Boolean resolved) {
		this(userId, before, limit, tab, types, resolved, null);
	}

	public GetFeedQuery(UserId userId, Optional<Instant> before, int limit, FeedTab tab) {
		this(userId, before, limit, tab, Set.of(), null);
	}

	public GetFeedQuery(UserId userId, Optional<Instant> before, int limit) {
		this(userId, before, limit, FeedTab.FOR_YOU, Set.of(), null);
	}
}
