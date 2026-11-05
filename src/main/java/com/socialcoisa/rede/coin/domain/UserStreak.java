package com.socialcoisa.rede.coin.domain;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.TreeSet;

/**
 * Daily presence streak. A day counts when the person does something meaningful (posts, comments,
 * reacts, plays the daily game, sends a message, joins a community or group, opens the wallet).
 *
 * Freeze: one per ISO week, never accumulated. When exactly ONE day was missed and the week of that
 * day has not used its freeze yet, the missed day is frozen: the streak survives but the frozen day
 * does not add to it. Two or more missed days break the streak. Everything is applied lazily, on
 * the next action or read; nothing runs at midnight.
 *
 * `lastDay` is the last day the streak covers (an active day or a frozen one).
 */
public record UserStreak(String userId, int currentDays, int longestDays, LocalDate lastDay,
		LocalDate lastFrozenDay) {

	/** Day boundaries follow Brazil, not the server zone. */
	public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	public static LocalDate today(Clock clock) {
		return LocalDate.now(clock.withZone(ZONE));
	}

	/**
	 * First touch of someone who had presence before this table existed: rebuilds the streak from the
	 * days that already have a presence reward (only the recent window the caller passes in).
	 */
	public static UserStreak fromPresenceDays(String userId, Collection<LocalDate> days) {
		if (days.isEmpty()) {
			return new UserStreak(userId, 0, 0, null, null);
		}
		int run = 0;
		int longest = 0;
		LocalDate previous = null;
		for (LocalDate day : new TreeSet<>(days)) {
			run = previous != null && previous.plusDays(1).equals(day) ? run + 1 : 1;
			longest = Math.max(longest, run);
			previous = day;
		}
		return new UserStreak(userId, run, longest, previous, null);
	}

	/** The state at the start of `today`: a single missed yesterday is frozen if the week allows; more breaks it. */
	public UserStreak settle(LocalDate today) {
		if (lastDay == null || !lastDay.isBefore(today.minusDays(1))) {
			return this;
		}
		LocalDate missed = today.minusDays(1);
		if (currentDays > 0 && lastDay.equals(today.minusDays(2)) && freezeUnusedInWeekOf(missed)) {
			return new UserStreak(userId, currentDays, longestDays, missed, missed);
		}
		return new UserStreak(userId, 0, longestDays, lastDay, lastFrozenDay);
	}

	/** Counts `today` as a present day (idempotent within the day). */
	public UserStreak touch(LocalDate today) {
		UserStreak settled = settle(today);
		if (today.equals(settled.lastDay)) {
			return settled;
		}
		boolean continues = settled.currentDays > 0 && today.minusDays(1).equals(settled.lastDay);
		int current = continues ? settled.currentDays + 1 : 1;
		return new UserStreak(userId, current, Math.max(settled.longestDays, current), today,
			settled.lastFrozenDay);
	}

	/** Freezes left in the week of `today` (0 or 1). Call on a settled streak. */
	public int freezesAvailable(LocalDate today) {
		return freezeUnusedInWeekOf(today) ? 1 : 0;
	}

	public boolean doneOn(LocalDate today) {
		return today.equals(lastDay) && currentDays > 0;
	}

	private boolean freezeUnusedInWeekOf(LocalDate day) {
		return lastFrozenDay == null || !weekOf(lastFrozenDay).equals(weekOf(day));
	}

	private static LocalDate weekOf(LocalDate day) {
		return day.with(DayOfWeek.MONDAY);
	}
}
