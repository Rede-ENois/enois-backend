package com.socialcoisa.rede.presence.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.presence.application.port.out.PresenceRepository;
import com.socialcoisa.rede.presence.domain.PresenceStatus;
import com.socialcoisa.rede.presence.domain.UserPresence;

@Repository
class JdbcPresenceRepository implements PresenceRepository {

	private final JdbcTemplate jdbc;

	JdbcPresenceRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<UserPresence> findByUserId(String userId) {
		return jdbc.query("select status, last_seen_at from user_presence where user_id = ?",
			(rs, row) -> new UserPresence(PresenceStatus.valueOf(rs.getString("status")),
				rs.getTimestamp("last_seen_at").toInstant()), userId).stream().findFirst();
	}

	@Override
	public void setStatus(String userId, PresenceStatus status, Instant seenAt) {
		jdbc.update("""
			insert into user_presence (user_id, status, last_seen_at) values (?, ?, ?)
			on duplicate key update status = values(status), last_seen_at = values(last_seen_at)
			""", userId, status.name(), Timestamp.from(seenAt));
	}

	@Override
	public void touch(String userId, Instant seenAt) {
		// Atualiza só o horário: um heartbeat concorrente não pode desfazer a escolha da pessoa.
		jdbc.update("""
			insert into user_presence (user_id, status, last_seen_at) values (?, 'AVAILABLE', ?)
			on duplicate key update last_seen_at = greatest(last_seen_at, values(last_seen_at))
			""", userId, Timestamp.from(seenAt));
	}
}
