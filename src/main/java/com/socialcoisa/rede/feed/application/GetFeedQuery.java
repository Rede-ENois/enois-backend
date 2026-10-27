package com.socialcoisa.rede.feed.application;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import com.socialcoisa.rede.feed.domain.FeedTab;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;

public record GetFeedQuery(UserId userId, Optional<Instant> before, int limit, FeedTab tab, Set<PostType> types) {

	public GetFeedQuery(UserId userId, Optional<Instant> before, int limit, FeedTab tab) {
		this(userId, before, limit, tab, Set.of());
	}

	public GetFeedQuery(UserId userId, Optional<Instant> before, int limit) {
		this(userId, before, limit, FeedTab.FOR_YOU, Set.of());
	}
}
