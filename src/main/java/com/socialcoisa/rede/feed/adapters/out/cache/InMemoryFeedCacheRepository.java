package com.socialcoisa.rede.feed.adapters.out.cache;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.feed.application.port.out.FeedCacheRepository;
import com.socialcoisa.rede.feed.domain.FeedItem;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

@Repository
class InMemoryFeedCacheRepository implements FeedCacheRepository {

	private static final int MAX_ITEMS_PER_USER = 500;

	private final Map<String, Deque<FeedItem>> feeds = new ConcurrentHashMap<>();

	@Override
	public void prepend(UserId userId, FeedItem item) {
		Deque<FeedItem> feed = feeds.computeIfAbsent(userId.value(), ignored -> new ArrayDeque<>());
		synchronized (feed) {
			feed.removeIf(existing -> existing.postId().equals(item.postId()));
			feed.addFirst(item);
			while (feed.size() > MAX_ITEMS_PER_USER) {
				feed.removeLast();
			}
		}
	}

	@Override
	public List<FeedItem> get(UserId userId, Instant before, int limit) {
		Deque<FeedItem> feed = feeds.get(userId.value());
		if (feed == null) {
			return List.of();
		}
		synchronized (feed) {
			return feed.stream()
				.filter(item -> item.createdAt().isBefore(before))
				.sorted(Comparator.comparing(FeedItem::createdAt).reversed())
				.limit(limit)
				.toList();
		}
	}

	@Override
	public void removeEverywhere(PostId postId) {
		List<Deque<FeedItem>> currentFeeds = new ArrayList<>(feeds.values());
		for (Deque<FeedItem> feed : currentFeeds) {
			synchronized (feed) {
				feed.removeIf(item -> item.postId().equals(postId));
			}
		}
	}
}
