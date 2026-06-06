package com.socialcoisa.rede.feed.application;

import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.feed.application.port.out.ConnectionGraphRepository;
import com.socialcoisa.rede.feed.application.port.out.FeedCacheRepository;
import com.socialcoisa.rede.feed.domain.FeedItem;
import com.socialcoisa.rede.feed.domain.FeedReason;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.UserId;

@Service
public class FeedDistributionService {

	static final double FRIEND_BASE_SCORE = 0.8;
	static final double FRIEND_OF_FRIEND_BASE_SCORE = 0.4;
	static final double TAG_BOOST = 0.1;
	static final double MAX_ENGAGEMENT_BOOST = 0.15;

	private final FeedCacheRepository feedCacheRepository;
	private final ConnectionGraphRepository connectionGraphRepository;
	private final int maxFanout;

	public FeedDistributionService(FeedCacheRepository feedCacheRepository,
			ConnectionGraphRepository connectionGraphRepository,
			@Value("${rede.feed.distribution.max-fanout:500}") int maxFanout) {
		this.feedCacheRepository = feedCacheRepository;
		this.connectionGraphRepository = connectionGraphRepository;
		this.maxFanout = maxFanout;
	}

	public void distribute(Post post) {
		double engagementBoost = calculateEngagementBoost(post);
		feedCacheRepository.prepend(post.authorId(), new FeedItem(post.id(), post.authorId(), 1.0, post.createdAt(),
			FeedReason.AUTHOR));
		Set<UserId> friends = connectionGraphRepository.findFriends(post.authorId());
		for (UserId friend : limitFanout(friends)) {
			double score = applyTagBoost(FRIEND_BASE_SCORE + engagementBoost, post, friend);
			feedCacheRepository.prepend(friend, new FeedItem(post.id(), post.authorId(),
				Math.min(1.0, score), post.createdAt(), FeedReason.FRIEND));
		}
		Set<UserId> secondDegree = new HashSet<>(connectionGraphRepository.findFriendsOfFriends(post.authorId()));
		secondDegree.remove(post.authorId());
		secondDegree.removeAll(friends);
		for (UserId userId : limitFanout(secondDegree)) {
			double score = applyTagBoost(FRIEND_OF_FRIEND_BASE_SCORE + engagementBoost, post, userId);
			feedCacheRepository.prepend(userId, new FeedItem(post.id(), post.authorId(),
				Math.min(0.8, score), post.createdAt(), FeedReason.FRIEND_OF_FRIEND));
		}
	}

	private Set<UserId> limitFanout(Set<UserId> candidates) {
		if (candidates.size() <= maxFanout) {
			return candidates;
		}
		Set<UserId> limited = new HashSet<>();
		int i = 0;
		for (UserId candidate : candidates) {
			if (i >= maxFanout) break;
			limited.add(candidate);
			i++;
		}
		return limited;
	}

	private double applyTagBoost(double baseScore, Post post, UserId userId) {
		if (post.tags() == null || post.tags().isEmpty()) {
			return baseScore;
		}
		Set<String> userTags = connectionGraphRepository.findUserTags(userId);
		if (userTags == null || userTags.isEmpty()) {
			return baseScore;
		}
		for (String postTag : post.tags()) {
			if (userTags.contains(postTag.toLowerCase())) {
				return baseScore + TAG_BOOST;
			}
		}
		return baseScore;
	}

	private double calculateEngagementBoost(Post post) {
		int total = post.counters().reactions() + post.counters().comments()
			+ post.counters().saves() + post.counters().shares();
		if (total <= 0) return 0.0;
		double raw = total * 0.02;
		return Math.min(raw, MAX_ENGAGEMENT_BOOST);
	}
}
