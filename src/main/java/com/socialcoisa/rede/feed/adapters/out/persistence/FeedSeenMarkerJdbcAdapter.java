package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.feed.application.port.out.FeedSeenMarkerRepository;
import com.socialcoisa.rede.feed.domain.UserId;

@Repository
class FeedSeenMarkerJdbcAdapter implements FeedSeenMarkerRepository {

	private final JdbcTemplate jdbc;

	FeedSeenMarkerJdbcAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Instant> lastSeenAt(UserId userId) {
		return jdbc.query("select last_seen_at from feed_seen_markers where user_id = ?",
				(rs, n) -> rs.getTimestamp(1).toInstant(), userId.value())
			.stream().findFirst();
	}

	@Override
	public void advance(UserId userId, Instant seenUntil, Instant now) {
		// Single upsert; greatest() keeps the marker monotonic even with concurrent or out-of-order calls.
		jdbc.update("insert into feed_seen_markers (user_id, last_seen_at, updated_at) values (?, ?, ?) "
				+ "on duplicate key update last_seen_at = greatest(last_seen_at, values(last_seen_at)), "
				+ "updated_at = values(updated_at)",
			userId.value(), Timestamp.from(seenUntil.truncatedTo(ChronoUnit.MICROS)),
			Timestamp.from(now.truncatedTo(ChronoUnit.MICROS)));
	}
}
