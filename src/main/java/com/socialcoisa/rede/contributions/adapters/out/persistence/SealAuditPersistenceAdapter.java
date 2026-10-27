package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.contributions.application.port.out.SealAuditRepository;

@Repository
class SealAuditPersistenceAdapter implements SealAuditRepository {

	private final JdbcTemplate jdbc;

	SealAuditPersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void append(String userId, String sealType, String action, String actorUserId, String note, Instant at) {
		jdbc.update("""
				insert into user_seal_events (id, user_id, seal_type, action, actor_user_id, note, created_at)
				values (?, ?, ?, ?, ?, ?, ?)""",
			"sealevt_" + UUID.randomUUID().toString().replace("-", ""), userId, sealType, action, actorUserId,
			note, java.sql.Timestamp.from(at));
	}

	@Override
	public List<Event> findByUser(String userId, int limit) {
		return jdbc.query("""
				select id, user_id, seal_type, action, actor_user_id, note, created_at
				from user_seal_events where user_id = ? order by created_at desc limit ?""",
			(rs, row) -> new Event(rs.getString("id"), rs.getString("user_id"), rs.getString("seal_type"),
				rs.getString("action"), rs.getString("actor_user_id"), rs.getString("note"),
				rs.getTimestamp("created_at").toInstant()),
			userId, Math.max(1, Math.min(limit, 200)));
	}
}
