package com.socialcoisa.rede.feed.application.port.in;

import com.socialcoisa.rede.feed.application.FeedPage;
import com.socialcoisa.rede.feed.application.GetFeedQuery;

public interface GetFeedUseCase {

	FeedPage getFeed(GetFeedQuery query);
}
