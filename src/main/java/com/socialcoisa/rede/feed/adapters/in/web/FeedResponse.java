package com.socialcoisa.rede.feed.adapters.in.web;

import java.time.Instant;
import java.util.List;

import com.socialcoisa.rede.feed.application.FeedPage;
import com.socialcoisa.rede.feed.application.PostView;
import com.socialcoisa.rede.feed.domain.FeedTab;

/**
 * GET /feed body: the page plus {@code lastSeenAt}, the person's "voce esta em dia" marker as it was
 * BEFORE this visit. Only the first page (no cursor) carries it; cursor pages send null and the front
 * keeps the value it got on the first page.
 *
 * <p>Only FOR_YOU carries it (maintainer decision: the marker belongs to "Para voce", the default
 * tab). FOR_YOU pages are date windows reordered by relevance inside the page, so the front shows the
 * posts newer than {@code lastSeenAt} first (newest first), then the divider, then the rest in API
 * order.
 */
record FeedResponse(List<PostView> items, String nextCursor, Instant lastSeenAt) {

	static boolean carriesMarker(FeedTab tab) {
		return tab == FeedTab.FOR_YOU;
	}

	static FeedResponse of(FeedPage page, Instant lastSeenAt) {
		return new FeedResponse(page.items(), page.nextCursor(), lastSeenAt);
	}
}
