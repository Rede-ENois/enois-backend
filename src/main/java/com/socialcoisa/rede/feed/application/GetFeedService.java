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
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.application.port.in.GetFeedUseCase;
import com.socialcoisa.rede.feed.application.port.in.GetProfilePostsUseCase;
import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.application.port.out.ConnectionGraphRepository;
import com.socialcoisa.rede.feed.application.port.out.FeedCacheRepository;
import com.socialcoisa.rede.feed.application.port.out.PostInteractionRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.FeedItem;
import com.socialcoisa.rede.feed.domain.FeedTab;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.ReactionType;
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
	private final PostInteractionRepository postInteractionRepository;
	private final CommentRepository commentRepository;
	private final CursorCodec cursorCodec;
	private static final Instant DEFAULT_BEFORE = Instant.parse("9999-12-31T23:59:59Z");

	public GetFeedService(PostRepository postRepository, FeedCacheRepository feedCacheRepository,
			ConnectionGraphRepository connectionGraphRepository, UserProfileRepository userProfileRepository,
			PostInteractionRepository postInteractionRepository, CommentRepository commentRepository,
			CursorCodec cursorCodec) {
		this.postRepository = postRepository;
		this.feedCacheRepository = feedCacheRepository;
		this.connectionGraphRepository = connectionGraphRepository;
		this.userProfileRepository = userProfileRepository;
		this.postInteractionRepository = postInteractionRepository;
		this.commentRepository = commentRepository;
		this.cursorCodec = cursorCodec;
	}

	@Override
	@Transactional(readOnly = true)
	public FeedPage getFeed(GetFeedQuery query) {
		int limit = normalizeLimit(query.limit());
		Instant before = query.before().orElse(DEFAULT_BEFORE);
		FeedTab tab = query.tab() != null ? query.tab() : FeedTab.FOR_YOU;
		List<Post> posts = switch (tab) {
			case FOR_YOU, FOLLOWING -> {
				Set<UserId> authors = new LinkedHashSet<>();
				authors.add(query.userId());
				authors.addAll(connectionGraphRepository.findFriends(query.userId()));
				yield postRepository.findActiveByAuthors(authors, before, limit);
			}
			case ALL -> postRepository.findActiveAll(before, limit);
			case DISCUSSIONS, RECENT -> {
				List<String> postIds = commentRepository.findPostIdsByAuthor(query.userId().value());
				yield postRepository.findByIds(postIds.stream().map(PostId::new).toList()).stream()
					.filter(Post::isActive)
					.filter(p -> p.createdAt().isBefore(before))
					.limit(limit)
					.toList();
			}
			case PROJECTS -> postRepository.findActiveByType(PostType.PROJECT, before, limit);
		};
		return page(enrich(posts, query.userId()), limit);
	}

	@Override
	@Transactional(readOnly = true)
	public FeedPage getProfilePosts(GetProfilePostsQuery query) {
		int limit = normalizeLimit(query.limit());
		Instant before = query.before().orElse(DEFAULT_BEFORE);
		List<Post> posts = postRepository.findActiveByAuthor(new UserId(query.username()), before, limit);
		return page(enrich(posts, query.viewerId()), limit);
	}

	@Transactional(readOnly = true)
	public FeedPage getSavedFeed(UserId userId) {
		List<String> savedIds = postInteractionRepository.findSavedPostIdsByUserId(userId);
		if (savedIds.isEmpty()) return new FeedPage(List.of(), null);
		List<PostId> postIds = savedIds.stream().map(PostId::new).toList();
		List<Post> posts = postRepository.findByIds(postIds).stream()
			.filter(Post::isActive).toList();
		List<PostView> enriched = enrich(posts, userId);
		List<PostView> ordered = savedIds.stream()
			.map(id -> enriched.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null))
			.filter(java.util.Objects::nonNull)
			.toList();
		return new FeedPage(ordered, null);
	}

	@Transactional(readOnly = true)
	public FeedPage getCommunityFeed(String communityId, Instant before, int limit, UserId currentUserId) {
		limit = normalizeLimit(limit);
		before = before == null ? DEFAULT_BEFORE : before;
		List<Post> posts = postRepository.findActiveByCommunity(communityId, before, limit);
		return page(enrich(posts, currentUserId), limit);
	}

	private List<PostView> enrich(List<Post> posts, UserId currentUserId) {
		if (posts.isEmpty()) {
			return List.of();
		}
		Set<String> authorIds = posts.stream()
			.map(p -> p.authorId().value())
			.collect(Collectors.toSet());
		Map<String, UserProfile> profiles = userProfileRepository.findAllByIds(authorIds).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
		List<PostId> postIds = posts.stream().map(Post::id).toList();
		Map<PostId, ReactionType> currentUserReactions = postInteractionRepository
			.findReactionsByUserAndPostIds(currentUserId, postIds);
		return posts.stream()
			.map(post -> {
				UserProfile profile = profiles.get(post.authorId().value());
				PostView view = PostView.from(post,
					profile != null ? profile.username() : null,
					profile != null ? profile.name() : post.authorId().value(),
					profile != null ? profile.avatar() : null);
				ReactionType reaction = currentUserReactions.get(post.id());
				return reaction != null ? view.withCurrentUserReaction(reaction) : view;
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
		return enrich(ordered, userId).stream()
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
