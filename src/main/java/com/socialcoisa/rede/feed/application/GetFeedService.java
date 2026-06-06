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
import com.socialcoisa.rede.feed.domain.FeedTab;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.CursorCodec;

@Service
public class GetFeedService implements GetFeedUseCase, GetProfilePostsUseCase {

	private final PostRepository postRepository;
	private final FeedCacheRepository feedCacheRepository;
	private final ConnectionGraphRepository connectionGraphRepository;
	private final UserProfileRepository userProfileRepository;
	private final CursorCodec cursorCodec;
	private static final Instant DEFAULT_BEFORE = Instant.parse("9999-12-31T23:59:59Z");

	public GetFeedService(PostRepository postRepository, FeedCacheRepository feedCacheRepository,
			ConnectionGraphRepository connectionGraphRepository, UserProfileRepository userProfileRepository,
			CursorCodec cursorCodec) {
		this.postRepository = postRepository;
		this.feedCacheRepository = feedCacheRepository;
		this.connectionGraphRepository = connectionGraphRepository;
		this.userProfileRepository = userProfileRepository;
		this.cursorCodec = cursorCodec;
	}

	@Override
	@Transactional(readOnly = true)
	public FeedPage getFeed(GetFeedQuery query) {
		int limit = normalizeLimit(query.limit());
		Instant before = query.before().orElse(DEFAULT_BEFORE);
		List<PostView> cached = fromCache(query.userId(), before, limit);
		if (!cached.isEmpty()) {
			return page(filterByTab(cached, query.tab()), limit);
		}
		Set<UserId> authors = new LinkedHashSet<>();
		authors.add(query.userId());
		authors.addAll(connectionGraphRepository.findFriends(query.userId()));
		authors.addAll(connectionGraphRepository.findFriendsOfFriends(query.userId()));
		List<Post> posts = postRepository.findActiveByAuthors(authors, before, limit);
		return page(filterByTab(enrich(posts), query.tab()), limit);
	}

	@Override
	@Transactional(readOnly = true)
	public FeedPage getProfilePosts(GetProfilePostsQuery query) {
		int limit = normalizeLimit(query.limit());
		Instant before = query.before().orElse(DEFAULT_BEFORE);
		List<Post> posts = postRepository.findActiveByAuthor(new UserId(query.username()), before, limit);
		return page(enrich(posts), limit);
	}

	@Transactional(readOnly = true)
	public FeedPage getCommunityFeed(String communityId, Instant before, int limit) {
		limit = normalizeLimit(limit);
		before = before == null ? DEFAULT_BEFORE : before;
		List<Post> posts = postRepository.findActiveByCommunity(communityId, before, limit);
		return page(enrich(posts), limit);
	}

	private List<PostView> enrich(List<Post> posts) {
		if (posts.isEmpty()) {
			return List.of();
		}
		Set<String> authorIds = posts.stream()
			.map(p -> p.authorId().value())
			.collect(Collectors.toSet());
		Map<String, UserProfile> profiles = userProfileRepository.findAllByIds(authorIds).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
		return posts.stream()
			.map(post -> {
				UserProfile profile = profiles.get(post.authorId().value());
				return PostView.from(post,
					profile != null ? profile.username() : null,
					profile != null ? profile.name() : post.authorId().value(),
					profile != null ? profile.avatar() : null);
			})
			.toList();
	}

	private List<PostView> filterByTab(List<PostView> posts, FeedTab tab) {
		if (tab == null || tab == FeedTab.FOR_YOU) {
			return posts;
		}
		return posts.stream()
			.filter(p -> matchesTab(p, tab))
			.toList();
	}

	private boolean matchesTab(PostView post, FeedTab tab) {
		return switch (tab) {
			case FOR_YOU -> true;
			case FOLLOWING -> true;
			case DISCUSSIONS -> post.type() == PostType.QUESTION;
			case PROJECTS -> post.type() == PostType.PROJECT;
		};
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
		Map<PostId, Post> postsById = postRepository.findByIds(orderedItems.values().stream().map(FeedItem::postId).toList())
			.stream()
			.filter(Post::isActive)
			.collect(Collectors.toMap(Post::id, Function.identity()));
		List<Post> ordered = new ArrayList<>();
		for (FeedItem item : orderedItems.values()) {
			Post post = postsById.get(item.postId());
			if (post != null) {
				ordered.add(post);
			}
		}
		return enrich(ordered).stream()
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
