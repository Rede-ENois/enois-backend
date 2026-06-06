package com.socialcoisa.rede.feed.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.application.port.in.GetFeedUseCase;
import com.socialcoisa.rede.feed.application.port.in.GetProfilePostsUseCase;
import com.socialcoisa.rede.feed.application.port.out.ConnectionGraphRepository;
import com.socialcoisa.rede.feed.application.port.out.FeedCacheRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.FeedItem;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.shared.web.CursorCodec;

@Service
public class GetFeedService implements GetFeedUseCase, GetProfilePostsUseCase {

	private final PostRepository postRepository;
	private final FeedCacheRepository feedCacheRepository;
	private final ConnectionGraphRepository connectionGraphRepository;
	private final CursorCodec cursorCodec;
	private static final Instant DEFAULT_BEFORE = Instant.parse("9999-12-31T23:59:59Z");

	public GetFeedService(PostRepository postRepository, FeedCacheRepository feedCacheRepository,
			ConnectionGraphRepository connectionGraphRepository, CursorCodec cursorCodec) {
		this.postRepository = postRepository;
		this.feedCacheRepository = feedCacheRepository;
		this.connectionGraphRepository = connectionGraphRepository;
		this.cursorCodec = cursorCodec;
	}

	@Override
	@Transactional(readOnly = true)
	public FeedPage getFeed(GetFeedQuery query) {
		int limit = normalizeLimit(query.limit());
		Instant before = query.before().orElse(DEFAULT_BEFORE);
		List<PostView> cached = fromCache(query.userId(), before, limit);
		if (!cached.isEmpty()) {
			return page(cached, limit);
		}
		Set<UserId> authors = new LinkedHashSet<>();
		authors.add(query.userId());
		authors.addAll(connectionGraphRepository.findFriends(query.userId()));
		authors.addAll(connectionGraphRepository.findFriendsOfFriends(query.userId()));
		List<PostView> posts = postRepository.findActiveByAuthors(authors, before, limit).stream()
			.map(PostView::from)
			.toList();
		return page(posts, limit);
	}

	@Override
	@Transactional(readOnly = true)
	public FeedPage getProfilePosts(GetProfilePostsQuery query) {
		int limit = normalizeLimit(query.limit());
		Instant before = query.before().orElse(DEFAULT_BEFORE);
		List<PostView> posts = postRepository.findActiveByAuthor(new UserId(query.username()), before, limit).stream()
			.map(PostView::from)
			.toList();
		return page(posts, limit);
	}

	private List<PostView> fromCache(UserId userId, Instant before, int limit) {
		List<FeedItem> items = feedCacheRepository.get(userId, before, limit);
		if (items.isEmpty()) {
			return List.of();
		}
		Map<String, FeedItem> orderedItems = new LinkedHashMap<>();
		for (FeedItem item : items) {
			orderedItems.put(item.postId().value(), item);
		}
		Map<PostId, Post> posts = postRepository.findByIds(orderedItems.values().stream().map(FeedItem::postId).toList())
			.stream()
			.filter(Post::isActive)
			.collect(Collectors.toMap(Post::id, Function.identity()));
		List<PostView> result = new ArrayList<>();
		for (FeedItem item : orderedItems.values()) {
			Post post = posts.get(item.postId());
			if (post != null) {
				result.add(PostView.from(post));
			}
		}
		return result.stream()
			.sorted(Comparator.comparing(PostView::createdAt).reversed())
			.toList();
	}

	private FeedPage page(List<PostView> posts, int limit) {
		String nextCursor = posts.size() == limit ? cursorCodec.encode(posts.get(posts.size() - 1).createdAt()) : null;
		return new FeedPage(posts, nextCursor);
	}

	private int normalizeLimit(int limit) {
		if (limit < 1) {
			return 20;
		}
		return Math.min(limit, 50);
	}
}
