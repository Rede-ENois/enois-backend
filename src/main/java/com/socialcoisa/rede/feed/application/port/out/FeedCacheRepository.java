package com.socialcoisa.rede.feed.application.port.out;

import java.time.Instant;
import java.util.List;

import com.socialcoisa.rede.feed.domain.FeedItem;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

public interface FeedCacheRepository {

	void prepend(UserId userId, FeedItem item);

	List<FeedItem> get(UserId userId, Instant before, int limit);

	void removeEverywhere(PostId postId);
}
