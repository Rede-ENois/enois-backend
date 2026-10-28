package com.socialcoisa.rede.cosmetics.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.cosmetics.application.port.out.CosmeticEventEntryRepository;

@Repository
class CosmeticEventEntryPersistenceAdapter implements CosmeticEventEntryRepository {

	private final NamedParameterJdbcTemplate jdbc;

	CosmeticEventEntryPersistenceAdapter(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<String> choiceOf(String eventId, String userId) {
		return jdbc.queryForList("select choice from cosmetic_event_entries where event_id = :event and user_id = :user",
			new MapSqlParameterSource("event", eventId).addValue("user", userId), String.class).stream().findFirst();
	}

	@Override
	public Map<String, Long> countByChoice(String eventId) {
		Map<String, Long> counts = new HashMap<>();
		jdbc.query("select choice, count(*) as total from cosmetic_event_entries where event_id = :event group by choice",
			new MapSqlParameterSource("event", eventId),
			rs -> {
				counts.put(rs.getString("choice"), rs.getLong("total"));
			});
		return counts;
	}

	@Override
	public List<String> eventIdsOf(String userId, Collection<String> eventIds) {
		return jdbc.queryForList(
			"select event_id from cosmetic_event_entries where user_id = :user and event_id in (:events)",
			new MapSqlParameterSource("user", userId).addValue("events", eventIds), String.class);
	}

	@Override
	public void insert(String eventId, String userId, String choice, Instant now) {
		jdbc.update("""
				insert into cosmetic_event_entries (event_id, user_id, choice, created_at)
				values (:event, :user, :choice, :at)""",
			new MapSqlParameterSource("event", eventId).addValue("user", userId).addValue("choice", choice)
				.addValue("at", Timestamp.from(now)));
	}
}
