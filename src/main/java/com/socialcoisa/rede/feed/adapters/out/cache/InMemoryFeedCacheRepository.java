package com.socialcoisa.rede.feed.adapters.out.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.feed.application.port.out.FeedCacheRepository;
import com.socialcoisa.rede.feed.domain.FeedItem;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

@Repository
class InMemoryFeedCacheRepository implements FeedCacheRepository {

	private final int maxItemsPerUser;
	private final Duration ttl;
	private final Clock clock;
	private final Map<String, Deque<FeedItem>> feeds = new ConcurrentHashMap<>();

	InMemoryFeedCacheRepository(@Value("${rede.feed.cache.max-items-per-user:500}") int maxItemsPerUser,
			@Value("${rede.feed.cache.ttl-seconds:3600}") int ttlSeconds, Clock clock) {
		this.maxItemsPerUser = maxItemsPerUser;
		this.ttl = Duration.ofSeconds(ttlSeconds);
		this.clock = clock;
	}

	@Override
	public void prepend(UserId userId, FeedItem item) {
		Deque<FeedItem> feed = feeds.computeIfAbsent(userId.value(), ignored -> new ArrayDeque<>());
		synchronized (feed) {
			feed.removeIf(existing -> existing.postId().equals(item.postId()));
			feed.addFirst(item);
			while (feed.size() > maxItemsPerUser) {
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
		Instant cutoff = clock.instant().minus(ttl);
		synchronized (feed) {
			return feed.stream()
				.filter(item -> item.createdAt().isAfter(cutoff))
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
