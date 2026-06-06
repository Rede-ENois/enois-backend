package com.socialcoisa.rede.feed.application;

import java.time.Instant;
import java.util.Optional;

import com.socialcoisa.rede.feed.domain.FeedTab;
import com.socialcoisa.rede.feed.domain.UserId;

public record GetFeedQuery(UserId userId, Optional<Instant> before, int limit, FeedTab tab) {

	public GetFeedQuery(UserId userId, Optional<Instant> before, int limit) {
		this(userId, before, limit, FeedTab.FOR_YOU);
	}
}
