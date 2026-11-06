package com.socialcoisa.rede.games.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.socialcoisa.rede.games.application.port.out.GameEventRepository;
import com.socialcoisa.rede.games.application.port.out.GameFavoriteRepository;
import com.socialcoisa.rede.games.application.port.out.GameRepository;
import com.socialcoisa.rede.games.application.port.out.GameUpdateRepository;
import com.socialcoisa.rede.games.domain.Game;
import com.socialcoisa.rede.games.domain.GameEventKind;
import com.socialcoisa.rede.games.domain.GameStatus;
import com.socialcoisa.rede.shared.application.CacheConfig;

/** "Jogados hoje": the 24-hour tally is cached and the games behind it are read in one batch. */
class TrendingTodayTests {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);

	private static Game game(String id, GameStatus status) {
		Game game = mock(Game.class);
		when(game.id()).thenReturn(id);
		when(game.status()).thenReturn(status);
		return game;
	}

	@Test
	void readsTheGamesInOneBatchKeepingTheTallyOrderAndOnlyPublished() {
		GameEventRepository events = mock(GameEventRepository.class);
		GameRepository games = mock(GameRepository.class);
		when(events.mostPlayedSince(eq(GameEventKind.PLAY_START), any(), anyInt())).thenReturn(List.of(
			new GameTally("g-top", 9, 4), new GameTally("g-suspended", 7, 3), new GameTally("g-gone", 6, 2),
			new GameTally("g-second", 5, 5), new GameTally("g-third", 1, 1)));
		Game top = game("g-top", GameStatus.PUBLISHED);
		Game second = game("g-second", GameStatus.PUBLISHED);
		Game third = game("g-third", GameStatus.PUBLISHED);
		Game suspended = game("g-suspended", GameStatus.SUSPENDED);
		when(games.findAllByIds(anyList())).thenReturn(List.of(third, suspended, second, top));
		var catalog = new GameCatalogService(games, mock(GameUpdateRepository.class), mock(GameFavoriteRepository.class),
			events, new TrendingGameTallies(events, CLOCK), CLOCK);

		var ranked = catalog.trendingToday(2);

		assertThat(ranked).extracting(item -> item.game().id()).containsExactly("g-top", "g-second");
		assertThat(ranked.get(1).plays()).isEqualTo(5);
		verify(games, times(1)).findAllByIds(List.of("g-top", "g-suspended", "g-gone", "g-second", "g-third"));
		verify(games, never()).findById(anyString());
		verify(events).mostPlayedSince(GameEventKind.PLAY_START, CLOCK.instant().minusSeconds(24 * 3600),
			TrendingGameTallies.CANDIDATES);
	}

	@Test
	void theTallyIsCachedBetweenFeedOpens() {
		GameEventRepository events = mock(GameEventRepository.class);
		when(events.mostPlayedSince(any(), any(), anyInt())).thenReturn(List.of(new GameTally("g", 1, 1)));
		try (var context = new AnnotationConfigApplicationContext()) {
			context.registerBean(GameEventRepository.class, () -> events);
			context.registerBean(Clock.class, () -> CLOCK);
			context.register(CacheConfig.class, TrendingGameTallies.class);
			context.refresh();
			TrendingGameTallies tallies = context.getBean(TrendingGameTallies.class);

			assertThat(tallies.lastDay()).isEqualTo(tallies.lastDay());

			verify(events, times(1)).mostPlayedSince(any(), any(), anyInt());
		}
	}
}
