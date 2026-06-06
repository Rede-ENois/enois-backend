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

	private final FeedCacheRepository feedCacheRepository;
	private final ConnectionGraphRepository connectionGraphRepository;

	public FeedDistributionService(FeedCacheRepository feedCacheRepository,
			ConnectionGraphRepository connectionGraphRepository) {
		this.feedCacheRepository = feedCacheRepository;
		this.connectionGraphRepository = connectionGraphRepository;
	}

	public void distribute(Post post) {
		feedCacheRepository.prepend(post.authorId(), new FeedItem(post.id(), post.authorId(), 1.0, post.createdAt(),
			FeedReason.AUTHOR));
		Set<UserId> friends = connectionGraphRepository.findFriends(post.authorId());
		for (UserId friend : friends) {
			feedCacheRepository.prepend(friend, new FeedItem(post.id(), post.authorId(), 0.8, post.createdAt(),
				FeedReason.FRIEND));
		}
		Set<UserId> secondDegree = new HashSet<>(connectionGraphRepository.findFriendsOfFriends(post.authorId()));
		secondDegree.remove(post.authorId());
		secondDegree.removeAll(friends);
		for (UserId userId : secondDegree) {
			feedCacheRepository.prepend(userId, new FeedItem(post.id(), post.authorId(), 0.4, post.createdAt(),
				FeedReason.FRIEND_OF_FRIEND));
		}
	}
}
