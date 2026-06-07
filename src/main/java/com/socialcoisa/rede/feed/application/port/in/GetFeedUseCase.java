package com.socialcoisa.rede.feed.application.port.in;

import com.socialcoisa.rede.feed.application.FeedPage;
import com.socialcoisa.rede.feed.application.GetFeedQuery;
import com.socialcoisa.rede.feed.domain.UserId;

public interface GetFeedUseCase {

	FeedPage getFeed(GetFeedQuery query);

	FeedPage getSavedFeed(UserId userId);
}
