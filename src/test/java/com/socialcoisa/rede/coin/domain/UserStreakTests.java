package com.socialcoisa.rede.coin.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Streak transitions: consecutive days, the weekly freeze, breaks and the lazy rebuild. */
class UserStreakTests {

	// 2026-10-26 is a Monday.
	private static final LocalDate MON = LocalDate.of(2026, 10, 26);

	private static UserStreak empty() {
		return new UserStreak("streak_u", 0, 0, null, null);
	}

	@Test
	void dayBoundaryFollowsSaoPauloNotUtc() {
		assertThat(UserStreak.today(Clock.fixed(Instant.parse("2026-10-30T02:59:00Z"), ZoneOffset.UTC)))
			.isEqualTo(LocalDate.of(2026, 10, 29));
		assertThat(UserStreak.today(Clock.fixed(Instant.parse("2026-10-30T03:00:00Z"), ZoneOffset.UTC)))
			.isEqualTo(LocalDate.of(2026, 10, 30));
	}

	@Test
	void consecutiveDaysIncrementAndTheSameDayIsIdempotent() {
		UserStreak s = empty().touch(MON).touch(MON.plusDays(1)).touch(MON.plusDays(2));
		assertThat(s.currentDays()).isEqualTo(3);
		assertThat(s.touch(MON.plusDays(2))).isEqualTo(s);
		assertThat(s.longestDays()).isEqualTo(3);
		assertThat(s.doneOn(MON.plusDays(2))).isTrue();
		assertThat(s.doneOn(MON.plusDays(3))).isFalse();
	}

	@Test
	void oneMissedDayConsumesTheWeeklyFreezeAndKeepsTheStreak() {
		UserStreak s = empty().touch(MON).touch(MON.plusDays(1)); // Mon, Tue
		assertThat(s.freezesAvailable(MON.plusDays(1))).isEqualTo(1);

		UserStreak afterGap = s.touch(MON.plusDays(3)); // Wed missed, back on Thu
		assertThat(afterGap.currentDays()).isEqualTo(3); // the frozen day does not count
		assertThat(afterGap.lastFrozenDay()).isEqualTo(MON.plusDays(2));
		assertThat(afterGap.freezesAvailable(MON.plusDays(3))).isZero();
	}

	@Test
	void aSecondMissInTheSameWeekBreaksTheStreak() {
		UserStreak s = empty().touch(MON).touch(MON.plusDays(1)).touch(MON.plusDays(3)); // Wed frozen
		UserStreak broken = s.touch(MON.plusDays(5)); // Fri missed, back on Sat
		assertThat(broken.currentDays()).isEqualTo(1);
		assertThat(broken.longestDays()).isEqualTo(3);
	}

	@Test
	void theFreezeRenewsEachIsoWeekAndDoesNotAccumulate() {
		UserStreak s = new UserStreak("streak_u", 5, 5, MON.plusDays(6), MON.plusDays(2)); // Sun, Wed was frozen
		UserStreak next = s.touch(MON.plusDays(8)); // Mon of the next week missed, back on Tue
		assertThat(next.currentDays()).isEqualTo(6);
		assertThat(next.lastFrozenDay()).isEqualTo(MON.plusDays(7));
		assertThat(empty().freezesAvailable(MON)).isEqualTo(1);
	}

	@Test
	void twoMissedDaysBreakEvenWithAFreezeAvailable() {
		UserStreak s = empty().touch(MON).touch(MON.plusDays(1));
		UserStreak broken = s.touch(MON.plusDays(4));
		assertThat(broken.currentDays()).isEqualTo(1);
		assertThat(broken.lastFrozenDay()).isNull();
		assertThat(s.settle(MON.plusDays(4)).currentDays()).isZero();
	}

	@Test
	void settleShowsAPendingFreezeWithoutCountingToday() {
		UserStreak s = empty().touch(MON).touch(MON.plusDays(1));
		UserStreak settled = s.settle(MON.plusDays(3));
		assertThat(settled.currentDays()).isEqualTo(2);
		assertThat(settled.doneOn(MON.plusDays(3))).isFalse();
		assertThat(settled.freezesAvailable(MON.plusDays(3))).isZero();
	}

	@Test
	void rebuildsFromExistingPresenceDays() {
		UserStreak s = UserStreak.fromPresenceDays("streak_u", List.of(
			MON.minusDays(10), MON.minusDays(9), MON.minusDays(8), MON.minusDays(7), // old run of 4
			MON.minusDays(2), MON.minusDays(1))); // current run of 2, last on Sunday
		assertThat(s.currentDays()).isEqualTo(2);
		assertThat(s.longestDays()).isEqualTo(4);
		assertThat(s.lastDay()).isEqualTo(MON.minusDays(1));
		assertThat(s.touch(MON).currentDays()).isEqualTo(3);
		assertThat(UserStreak.fromPresenceDays("streak_u", List.of()).touch(MON).currentDays()).isEqualTo(1);
	}
}
