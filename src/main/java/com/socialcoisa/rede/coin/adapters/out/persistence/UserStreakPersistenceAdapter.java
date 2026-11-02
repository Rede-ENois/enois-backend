package com.socialcoisa.rede.coin.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.coin.application.port.out.UserStreakRepository;
import com.socialcoisa.rede.coin.domain.UserStreak;

@Repository
class UserStreakPersistenceAdapter implements UserStreakRepository {

	private final JdbcTemplate jdbc;
	private final Clock clock;

	UserStreakPersistenceAdapter(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	public Optional<UserStreak> find(String userId) {
		return jdbc.query("select current_days, longest_days, last_day, last_frozen_day from user_streaks where user_id=?",
			(r, n) -> new UserStreak(userId, r.getInt(1), r.getInt(2), r.getObject(3, LocalDate.class),
				r.getObject(4, LocalDate.class)), userId).stream().findFirst();
	}

	@Override
	public void save(UserStreak s) {
		Timestamp now = Timestamp.from(clock.instant());
		int updated = jdbc.update("update user_streaks set current_days=?, longest_days=?, last_day=?, last_frozen_day=?,"
			+ " updated_at=? where user_id=?", s.currentDays(), s.longestDays(), s.lastDay(), s.lastFrozenDay(), now,
			s.userId());
		if (updated > 0) {
			return;
		}
		try {
			jdbc.update("insert into user_streaks (user_id, current_days, longest_days, last_day, last_frozen_day,"
				+ " updated_at) values (?,?,?,?,?,?)", s.userId(), s.currentDays(), s.longestDays(), s.lastDay(),
				s.lastFrozenDay(), now);
		} catch (DuplicateKeyException concurrentFirstTouch) {
			// Two actions created the row at the same time: both computed the same day, keep the stored one.
		}
	}
}
