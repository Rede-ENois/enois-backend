package com.socialcoisa.rede.feed.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.feed.application.port.out.ConnectionGraphRepository;
import com.socialcoisa.rede.feed.application.port.out.PostCommunityReader;
import com.socialcoisa.rede.feed.application.port.out.PostInteractionRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepostRepository;
import com.socialcoisa.rede.feed.application.port.out.PollRepository;
import com.socialcoisa.rede.feed.application.port.out.ReaderPreferencesReader;
import com.socialcoisa.rede.feed.application.port.out.SubscribedBlogAuthorsReader;
import com.socialcoisa.rede.feed.domain.FeedTab;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.in.GetMediaSummariesUseCase;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.contributions.application.port.out.UserSealRepository;

/**
 * "Ocultar reposts" no feed: o repost com opinião sai e o original fica, em todas as abas. O cursor
 * sai da janela que veio do banco, nunca da lista já filtrada — uma janela só de reposts escondidos
 * deixa a página vazia, mas ainda precisa apontar para a continuação (e não pode estourar ao pegar
 * o "último" de uma lista vazia).
 */
class HideRepostsCursorTests {

	private final PostRepository posts = mock(PostRepository.class);
	private final ConnectionGraphRepository connections = mock(ConnectionGraphRepository.class);
	private final UserProfileRepository profiles = mock(UserProfileRepository.class);
	private final UserSealRepository seals = mock(UserSealRepository.class);
	private final PostInteractionRepository interactions = mock(PostInteractionRepository.class);
	private final PostCommunityReader communities = mock(PostCommunityReader.class);
	private final SubscribedBlogAuthorsReader blogs = mock(SubscribedBlogAuthorsReader.class);
	private final ReaderPreferencesReader preferences = mock(ReaderPreferencesReader.class);
	private final PostNoteService notes = mock(PostNoteService.class);
	private final PollRepository polls = mock(PollRepository.class);
	private final BlockedUsersReader blockedUsers = mock(BlockedUsersReader.class);
	private final PostRepostRepository reposts = mock(PostRepostRepository.class);
	private final GetMediaSummariesUseCase media = mock(GetMediaSummariesUseCase.class);

	private final GetFeedService feed = new GetFeedService(posts, null, null, connections, profiles, seals,
		interactions, null, new CursorCodec(), blockedUsers, communities, null, blogs, null, preferences, notes,
		polls, reposts, media, mock(com.socialcoisa.rede.shared.sanctions.SanctionGateReader.class));

	private static final UserId READER = new UserId("synthetic_reader");
	private static final Instant NEWEST = Instant.parse("2030-01-01T12:00:00Z");

	@Test
	void trendingHidesRepostsButKeepsTheOriginals() {
		when(preferences.hideReposts("synthetic_reader")).thenReturn(true);
		when(posts.findTrending(any(), any(), nullable(TrendingCursor.class), eq(3)))
			.thenReturn(List.of(repost("r1", NEWEST), post("p1", NEWEST.minusSeconds(1)),
				post("p2", NEWEST.minusSeconds(2))));

		FeedPage page = feed.getFeed(new GetFeedQuery(READER, Optional.empty(), 2, FeedTab.TRENDING));

		assertThat(page.items()).extracting(w -> w.id()).containsExactly("p1");
		assertThat(page.nextCursor()).isNotNull();
	}

	@Test
	void trendingKeepsTheCursorWhenTheWholeWindowIsHidden() {
		when(preferences.hideReposts("synthetic_reader")).thenReturn(true);
		when(posts.findTrending(any(), any(), nullable(TrendingCursor.class), eq(3)))
			.thenReturn(List.of(repost("r1", NEWEST), repost("r2", NEWEST.minusSeconds(1)),
				repost("r3", NEWEST.minusSeconds(2))));

		FeedPage page = feed.getFeed(new GetFeedQuery(READER, Optional.empty(), 2, FeedTab.TRENDING));

		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNotNull();
	}

	@Test
	void allFeedHidesRepostsButKeepsTheOriginals() {
		when(preferences.blockedTopics("synthetic_reader")).thenReturn(List.of());
		when(preferences.hideReposts("synthetic_reader")).thenReturn(true);
		when(posts.findActiveAll(any(), eq(2))).thenReturn(List.of(repost("r1", NEWEST),
			post("p1", NEWEST.minusSeconds(1))));

		FeedPage page = feed.getFeed(new GetFeedQuery(READER, Optional.empty(), 2, FeedTab.ALL));

		assertThat(page.items()).extracting(w -> w.id()).containsExactly("p1");
		assertThat(page.nextCursor()).isNotNull();
	}

	@Test
	void allFeedKeepsTheCursorWhenTheWholeWindowIsHidden() {
		Instant oldest = NEWEST.minusSeconds(1);
		when(preferences.blockedTopics("synthetic_reader")).thenReturn(List.of());
		when(preferences.hideReposts("synthetic_reader")).thenReturn(true);
		when(posts.findActiveAll(any(), eq(2))).thenReturn(List.of(repost("r1", NEWEST), repost("r2", oldest)));

		FeedPage page = feed.getFeed(new GetFeedQuery(READER, Optional.empty(), 2, FeedTab.ALL));

		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isEqualTo(oldest.toString());
	}

	@Test
	void followingHidesRepostsButKeepsTheOriginals() {
		when(preferences.hideReposts("synthetic_reader")).thenReturn(true);
		when(connections.findFriends(READER)).thenReturn(Set.of(new UserId("friend")));
		when(posts.findActiveByAuthors(any(), any(), eq(2)))
			.thenReturn(List.of(repost("r1", NEWEST), post("p1", NEWEST.minusSeconds(1))));

		FeedPage page = feed.getFeed(new GetFeedQuery(READER, Optional.empty(), 2, FeedTab.FOLLOWING));

		assertThat(page.items()).extracting(w -> w.id()).containsExactly("p1");
	}

	@Test
	void forYouHidesRepostsButKeepsTheOriginals() {
		when(preferences.hideReposts("synthetic_reader")).thenReturn(true);
		when(preferences.blockedTopics("synthetic_reader")).thenReturn(List.of());
		when(connections.findFriends(READER)).thenReturn(Set.of(new UserId("friend")));
		when(blogs.authorsFollowedBy("synthetic_reader")).thenReturn(Set.of());
		when(posts.findActiveByAuthors(any(), any(), eq(6)))
			.thenReturn(List.of(repost("r1", NEWEST), post("p1", NEWEST.minusSeconds(1)),
				post("p2", NEWEST.minusSeconds(2))));

		FeedPage page = feed.getFeed(new GetFeedQuery(READER, Optional.empty(), 2, FeedTab.FOR_YOU));

		assertThat(page.items()).extracting(w -> w.id()).containsExactly("p1");
		assertThat(page.nextCursor()).isNotNull();
	}

	@Test
	void forYouDoesNotThrowWhenTheWindowHoldsOnlyReposts() {
		when(preferences.hideReposts("synthetic_reader")).thenReturn(true);
		when(preferences.blockedTopics("synthetic_reader")).thenReturn(List.of());
		when(connections.findFriends(READER)).thenReturn(Set.of());
		when(blogs.authorsFollowedBy("synthetic_reader")).thenReturn(Set.of());
		when(posts.findActiveByAuthors(any(), any(), eq(3)))
			.thenReturn(List.of(repost("r1", NEWEST), repost("r2", NEWEST.minusSeconds(1))));

		FeedPage page = feed.getFeed(new GetFeedQuery(READER, Optional.empty(), 1, FeedTab.FOR_YOU));

		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNotNull();
	}

	private static Post repost(String id, Instant createdAt) {
		return post(id, createdAt).markRepostOf("post_original", "autor_original", true);
	}

	private static Post post(String id, Instant createdAt) {
		return Post.publish(new PostId(id), new UserId("author"), PostType.INSIGHT, "conteudo", null,
			List.of(), List.of(), null, createdAt);
	}
}
