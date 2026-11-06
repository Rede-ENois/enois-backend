package com.socialcoisa.rede.games.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.games.application.port.out.GameEventRepository;
import com.socialcoisa.rede.games.domain.GameEventKind;
import com.socialcoisa.rede.shared.application.CacheConfig;

/**
 * The aggregate behind "jogados hoje": PLAY_START sessions of the last 24 hours grouped by game.
 *
 * It is public and opened with every feed, so the grouped read is cached ({@link CacheConfig#TRENDING_GAMES}).
 * Only the tallies are cached: the games themselves are read per request, so a game that was
 * suspended or edited never comes back from the cache.
 */
@Component
public class TrendingGameTallies {

	/** Candidates read per window: twice the card's maximum, so suspended games cannot shrink it. */
	static final int CANDIDATES = GameCatalogService.MAX_TRENDING * 2;

	private final GameEventRepository events;
	private final Clock clock;

	public TrendingGameTallies(GameEventRepository events, Clock clock) {
		this.events = events;
		this.clock = clock;
	}

	@Cacheable(cacheNames = CacheConfig.TRENDING_GAMES, key = "'last24h'", sync = true)
	@Transactional(readOnly = true)
	public List<GameTally> lastDay() {
		return List.copyOf(events.mostPlayedSince(GameEventKind.PLAY_START, clock.instant().minus(Duration.ofHours(24)),
			CANDIDATES));
	}
}
