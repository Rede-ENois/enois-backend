package com.socialcoisa.rede.notifications.adapters.out.persistence;

import java.sql.Timestamp;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.notifications.application.port.out.NotificationPreferenceRepository;
import com.socialcoisa.rede.notifications.domain.NotificationPreferences;

@Repository
class NotificationPreferencePersistenceAdapter implements NotificationPreferenceRepository {

	private final JdbcTemplate jdbc;

	NotificationPreferencePersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<NotificationPreferences> find(String userId) {
		return jdbc.query("""
				select weekly_digest, push_social, push_messages, push_groups, push_jobs, push_system, updated_at
				from notification_preferences where user_id = ?""",
			(rs, row) -> new NotificationPreferences(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3),
				rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6), rs.getTimestamp(7).toInstant()),
			userId).stream().findFirst();
	}

	@Override
	public void save(String userId, NotificationPreferences p) {
		if (update(userId, p) > 0) {
			return;
		}
		try {
			jdbc.update("""
					insert into notification_preferences (user_id, weekly_digest, push_social, push_messages,
					push_groups, push_jobs, push_system, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)""",
				userId, p.weeklyDigest(), p.pushSocial(), p.pushMessages(), p.pushGroups(), p.pushJobs(),
				p.pushSystem(), Timestamp.from(p.updatedAt()));
		}
		catch (DuplicateKeyException concurrentInsert) {
			// Another request created the row first: last write wins, as with any other update.
			update(userId, p);
		}
	}

	private int update(String userId, NotificationPreferences p) {
		return jdbc.update("""
				update notification_preferences set weekly_digest = ?, push_social = ?, push_messages = ?,
				push_groups = ?, push_jobs = ?, push_system = ?, updated_at = ? where user_id = ?""",
			p.weeklyDigest(), p.pushSocial(), p.pushMessages(), p.pushGroups(), p.pushJobs(), p.pushSystem(),
			Timestamp.from(p.updatedAt()), userId);
	}
}
