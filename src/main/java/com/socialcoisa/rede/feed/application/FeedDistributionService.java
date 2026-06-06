package com.socialcoisa.rede.feed.application;

import java.util.HashSet;
import java.util.Set;

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
	static final double MAX_ENGAGEMENT_BOOST = 0.15;

	private final FeedCacheRepository feedCacheRepository;
	private final ConnectionGraphRepository connectionGraphRepository;

	public FeedDistributionService(FeedCacheRepository feedCacheRepository,
			ConnectionGraphRepository connectionGraphRepository) {
		this.feedCacheRepository = feedCacheRepository;
		this.connectionGraphRepository = connectionGraphRepository;
	}

	public void distribute(Post post) {
		double engagementBoost = calculateEngagementBoost(post);
		feedCacheRepository.prepend(post.authorId(), new FeedItem(post.id(), post.authorId(), 1.0, post.createdAt(),
			FeedReason.AUTHOR));
		Set<UserId> friends = connectionGraphRepository.findFriends(post.authorId());
		for (UserId friend : friends) {
			feedCacheRepository.prepend(friend, new FeedItem(post.id(), post.authorId(),
				Math.min(1.0, FRIEND_BASE_SCORE + engagementBoost), post.createdAt(), FeedReason.FRIEND));
		}
		Set<UserId> secondDegree = new HashSet<>(connectionGraphRepository.findFriendsOfFriends(post.authorId()));
		secondDegree.remove(post.authorId());
		secondDegree.removeAll(friends);
		for (UserId userId : secondDegree) {
			feedCacheRepository.prepend(userId, new FeedItem(post.id(), post.authorId(),
				Math.min(0.8, FRIEND_OF_FRIEND_BASE_SCORE + engagementBoost), post.createdAt(),
				FeedReason.FRIEND_OF_FRIEND));
		}
	}

	private double calculateEngagementBoost(Post post) {
		int total = post.counters().reactions() + post.counters().comments()
			+ post.counters().saves() + post.counters().shares();
		if (total <= 0) return 0.0;
		double raw = total * 0.02;
		return Math.min(raw, MAX_ENGAGEMENT_BOOST);
	}
}
