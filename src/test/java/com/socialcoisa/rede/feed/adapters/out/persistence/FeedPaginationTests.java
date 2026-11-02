package com.socialcoisa.rede.feed.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.socialcoisa.rede.feed.application.FeedPage;
import com.socialcoisa.rede.feed.application.GetFeedQuery;
import com.socialcoisa.rede.feed.application.GetFeedService;
import com.socialcoisa.rede.feed.application.PostView;
import com.socialcoisa.rede.feed.application.TrendingCursor;
import com.socialcoisa.rede.feed.domain.Comment;
import com.socialcoisa.rede.feed.domain.FeedTab;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.feed.domain.UserId;

@SpringBootTest
@Transactional
class FeedPaginationTests {

	@Autowired private GetFeedService feed;
	@Autowired private PostPersistenceAdapter posts;
	@Autowired private SpringDataReactionJpaRepository reactions;
	@Autowired private SpringDataCommentJpaRepository comments;
	@PersistenceContext private EntityManager entityManager;

	private final String prefix = "pagination_" + UUID.randomUUID().toString().substring(0, 8);
	private final UserId reader = new UserId(prefix + "_reader");
	private final Instant base = Instant.parse("2090-01-01T12:00:00Z");

	@Test
	void unseenReachesPostsAfterAnEntireWindowOfInteractedPosts() {
		for (int i = 0; i < 100; i++) {
			Post post = save(i, base.minusSeconds(i));
			if (i % 2 == 0) react(post, i);
			else comment(post, i);
		}
		Post unread = save(100, base.minusSeconds(100));
		FeedPage page = page(FeedTab.UNSEEN, base.plusSeconds(1).toString(), 20);
		assertThat(page.items()).extracting(PostView::id).contains(unread.id().value());
		assertThat(page.items()).extracting(PostView::id)
			.doesNotContain(prefix + "_post_0", prefix + "_post_1");
	}

	@Test
	void interactedPaginatesPastThreePagesOfReactions() {
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 61; i++) {
			Post post = save(i, base.minusSeconds(i));
			react(post, i);
			expected.add(post.id().value());
		}
		assertThat(readAll(FeedTab.INTERACTED, base.plusSeconds(1).toString(), 20))
			.containsExactlyElementsOf(expected);
	}

	@Test
	void interactedUnifiesCommentsAndReactionsWithoutRepeatingPosts() {
		Post first = save(0, base);
		Post second = save(1, base.minusSeconds(1));
		Post untouched = save(2, base.minusSeconds(2));
		react(first, 0);
		comment(first, 0);
		comment(first, 1);
		comment(second, 2);
		assertThat(readAll(FeedTab.INTERACTED, base.plusSeconds(1).toString(), 1))
			.containsExactly(first.id().value(), second.id().value())
			.doesNotContain(untouched.id().value());
	}

	@Test
	void trendingPaginatesTheRankingInsteadOfRepeatingItsFirstPage() {
		Instant now = Instant.now().minusSeconds(60);
		List<Post> expected = new ArrayList<>();
		for (int i = 0; i < 45; i++) {
			Post post = newPost(i, now.minusSeconds(i / 2));
			for (int n = 0; n < 100 + (i / 2 % 3); n++) post.addReaction();
			expected.add(posts.save(post));
		}
		expected.sort(Comparator.comparingInt((Post p) -> p.counters().reactions()).reversed()
			.thenComparing(Post::createdAt, Comparator.reverseOrder())
			.thenComparing(p -> p.id().value(), Comparator.reverseOrder()));
		List<String> own = readAll(FeedTab.TRENDING, null, 20).stream()
			.filter(id -> id.startsWith(prefix)).toList();
		assertThat(own).containsExactlyElementsOf(expected.stream().map(p -> p.id().value()).toList());
	}

	@Test
	void trendingKeepsItsWindowAndExcludesPostsOutsideItsExistingScope() {
		Instant until = Instant.now().minusSeconds(60);
		Post eligible = save(0, until.minusSeconds(1));
		Post afterWindow = save(1, until.plusSeconds(1));
		Post old = save(2, until.minusSeconds(86_401));
		Post deleted = newPost(3, until.minusSeconds(2));
		deleted.delete(until);
		posts.save(deleted);
		for (PostType type : List.of(PostType.BLOG, PostType.BUSINESS)) {
			posts.save(Post.publish(new PostId(prefix + type), reader, type, "Synthetic", null,
				List.of(), List.of(), null, until.minusSeconds(3)));
		}
		Post channel = posts.save(Post.publish(new PostId(prefix + "_channel_post"), reader, PostType.INSIGHT,
			"Synthetic", null, List.of(), List.of(), null, null, prefix + "_channel", until.minusSeconds(4),
			com.socialcoisa.rede.feed.domain.AuthorIdentity.SOCIAL));
		String cursor = new TrendingCursor(until, 1, until, "synthetic_boundary").encode();
		List<String> ids = readAll(FeedTab.TRENDING, cursor, 20);
		assertThat(ids).contains(eligible.id().value()).doesNotContain(afterWindow.id().value(), old.id().value(),
			deleted.id().value(), prefix + PostType.BLOG, prefix + PostType.BUSINESS, channel.id().value());
	}

	private List<String> readAll(FeedTab tab, String cursor, int limit) {
		List<String> ids = new ArrayList<>();
		for (int pageNumber = 0; pageNumber < 10; pageNumber++) {
			FeedPage result = page(tab, cursor, limit);
			List<String> current = result.items().stream().map(PostView::id).toList();
			if (!ids.isEmpty()) assertThat(current).doesNotContainAnyElementsOf(ids);
			ids.addAll(current);
			if (result.nextCursor() == null) return ids;
			assertThat(result.nextCursor()).isNotEqualTo(cursor);
			cursor = result.nextCursor();
		}
		throw new AssertionError("Pagination did not finish within the fixture's page count");
	}

	private FeedPage page(FeedTab tab, String cursor, int limit) {
		entityManager.flush();
		entityManager.clear();
		return feed.getFeed(new GetFeedQuery(reader,
			tab == FeedTab.TRENDING ? Optional.empty() : Optional.ofNullable(cursor).map(Instant::parse),
			limit, tab, java.util.Set.of(), null, tab == FeedTab.TRENDING ? TrendingCursor.decode(cursor) : null));
	}

	private Post newPost(int index, Instant at) {
		return Post.publish(new PostId(prefix + "_post_" + index), new UserId(prefix + "_author"),
			PostType.INSIGHT, "Synthetic pagination fixture", null, List.of(), List.of(), null, at);
	}

	private Post save(int index, Instant at) {
		return posts.save(newPost(index, at));
	}

	private void react(Post post, int index) {
		reactions.save(new ReactionJpaEntity(prefix + "_reaction_" + index, post.id().value(),
			reader.value(), ReactionType.LIKE, base.minusSeconds(index)));
	}

	private void comment(Post post, int index) {
		comments.save(CommentJpaEntity.fromDomain(Comment.create(prefix + "_comment_" + index,
			post.id(), reader, null, "Synthetic comment", List.of(), base.minusSeconds(index))));
	}
}
