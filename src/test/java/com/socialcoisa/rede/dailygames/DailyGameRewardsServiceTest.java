package com.socialcoisa.rede.dailygames;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

/** Settlement pays each credit on its own, retries a busy ledger, and pays only eligible accounts. */
class DailyGameRewardsServiceTest {

	private static final LocalDate DAY = LocalDate.of(2026, 10, 16);

	private static final Instant DAY_START = Instant.parse("2026-10-16T03:00:00Z");

	private final CoinService coins = mock(CoinService.class);
	private final DailyWordService word = mock(DailyWordService.class);
	private final DailyMazeService maze = mock(DailyMazeService.class);
	private final DailyBlocksService blocks = mock(DailyBlocksService.class);
	private final UserProfileRepository profiles = mock(UserProfileRepository.class);
	private DailyGameRewardsService rewards;

	@BeforeEach
	void setUp() {
		when(coins.scaledReward(anyLong())).thenAnswer(call -> call.getArgument(0));
		when(maze.payoutOrder(DAY)).thenReturn(List.of());
		when(blocks.payoutOrder(DAY)).thenReturn(List.of());
		rewards = new DailyGameRewardsService(coins, mock(NotificationService.class), profiles,
			Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC), mock(PlatformTransactionManager.class),
			word, maze, blocks);
	}

	private void profiles(Map<String, UserProfile> byId) {
		when(profiles.findAllByIds(any())).thenAnswer(call -> ((Collection<?>) call.getArgument(0)).stream()
			.filter(byId::containsKey).map(byId::get).toList());
	}

	private static UserProfile account(String id, boolean verified, Instant createdAt) {
		UserProfile profile = mock(UserProfile.class);
		when(profile.id()).thenReturn(id);
		when(profile.identityVerified()).thenReturn(verified);
		when(profile.createdAt()).thenReturn(createdAt);
		return profile;
	}

	private static UserProfile established(String id) {
		return account(id, true, DAY_START.minus(Duration.ofDays(30)));
	}

	private static String ref(String userId) {
		return "jogos:palavras:" + DAY + ":" + userId;
	}

	private static ApiException busyLedger() {
		return new ApiException(409, "COIN_CONCURRENCY", "Movimentação concorrente; tente de novo.");
	}

	@Test
	void aBusyLedgerIsRetriedAndAFailingAccountDoesNotBlockTheOthers() {
		when(word.payoutOrder(DAY)).thenReturn(List.of("retried", "stuck", "after"));
		profiles(Map.of("retried", established("retried"), "stuck", established("stuck"), "after", established("after")));
		doThrow(busyLedger()).doThrow(new CannotAcquireLockException("lock wait")).doNothing()
			.when(coins).reward(eq("retried"), anyLong(), eq(ref("retried")), anyString());
		doThrow(busyLedger()).when(coins).reward(eq("stuck"), anyLong(), eq(ref("stuck")), anyString());

		assertThat(rewards.settle(DAY)).isEqualTo(2);

		verify(coins, times(DailyGameRewardsService.MAX_ATTEMPTS)).reward(eq("retried"), eq(DailyGameRewardsService.PRIZE_TOP1),
			eq(ref("retried")), anyString());
		verify(coins, times(DailyGameRewardsService.MAX_ATTEMPTS)).reward(eq("stuck"), anyLong(), eq(ref("stuck")),
			anyString());
		verify(coins).reward(eq("after"), eq(DailyGameRewardsService.PRIZE_PODIUM), eq(ref("after")), anyString());
	}

	@Test
	void aNonTransientFailureIsNotRetried() {
		when(word.payoutOrder(DAY)).thenReturn(List.of("broken"));
		profiles(Map.of("broken", established("broken")));
		doThrow(new ApiException(400, "INSUFFICIENT_SPARKS", "Faíscas insuficientes.")).when(coins)
			.reward(eq("broken"), anyLong(), anyString(), anyString());

		assertThat(rewards.settle(DAY)).isZero();
		verify(coins, times(1)).reward(eq("broken"), anyLong(), anyString(), anyString());
	}

	@Test
	void onlyVerifiedAccountsOlderThanTheNewAccountWindowArePaidAndRanksSkipTheRest() {
		when(word.payoutOrder(DAY)).thenReturn(List.of("fresh", "unverified", "unknown", "edge", "veteran"));
		profiles(Map.of(
			"fresh", account("fresh", true, DAY_START.minus(Duration.ofDays(6))),
			"unverified", account("unverified", false, DAY_START.minus(Duration.ofDays(30))),
			"edge", account("edge", true, DAY_START.minus(Duration.ofDays(7))),
			"veteran", established("veteran")));

		assertThat(rewards.settle(DAY)).isEqualTo(2);

		verify(coins, never()).reward(eq("fresh"), anyLong(), anyString(), anyString());
		verify(coins, never()).reward(eq("unverified"), anyLong(), anyString(), anyString());
		verify(coins, never()).reward(eq("unknown"), anyLong(), anyString(), anyString());
		verify(coins).reward(eq("edge"), eq(DailyGameRewardsService.PRIZE_TOP1), eq(ref("edge")), startsWith("Jogo diário"));
		verify(coins).reward(eq("veteran"), eq(DailyGameRewardsService.PRIZE_PODIUM), eq(ref("veteran")), anyString());
	}
}
