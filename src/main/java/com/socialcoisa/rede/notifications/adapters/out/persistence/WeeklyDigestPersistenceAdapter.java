package com.socialcoisa.rede.notifications.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.notifications.application.port.out.WeeklyDigestRepository;
import com.socialcoisa.rede.notifications.domain.DigestItem;

/**
 * Plain SQL over the tables of other modules: the digest only reads them, and one query per
 * section is cheaper than loading each module's aggregates for every recipient.
 */
@Repository
class WeeklyDigestPersistenceAdapter implements WeeklyDigestRepository {

	/**
	 * Neither side of a block shows up in the other's digest, and someone the reader muted stays
	 * out of the reader's digest (a mute is one-way, like in the feed).
	 */
	private static final String NOT_HIDDEN = """
		not exists (select 1 from user_blocks b where (b.blocker_id = ? and b.blocked_id = %1$s)
		or (b.blocker_id = %1$s and b.blocked_id = ?))
		and not exists (select 1 from user_mutes mu where mu.muter_id = ? and mu.muted_id = %1$s)""";

	private final JdbcTemplate jdbc;

	WeeklyDigestPersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<String> eligibleUserIds(String weekKey, String afterUserId, int limit) {
		return jdbc.queryForList("""
				select u.id from users u
				where u.email_verified_at is not null and u.id > ?
				and not exists (select 1 from notification_preferences p where p.user_id = u.id and p.weekly_digest = false)
				and not exists (select 1 from digest_deliveries d where d.user_id = u.id and d.week_key = ?)
				and not exists (select 1 from user_sanctions s where s.user_id = u.id and s.status = 'ACTIVE'
					and s.type in ('SUSPENDED', 'BAN', 'PERMABAN'))
				order by u.id limit ?""", String.class, afterUserId, weekKey, limit);
	}

	@Override
	public boolean claim(String userId, String weekKey, Instant now) {
		try {
			jdbc.update("insert into digest_deliveries (user_id, week_key, sent_at) values (?, ?, ?)", userId, weekKey,
				Timestamp.from(now));
			return true;
		}
		catch (DuplicateKeyException alreadyClaimed) {
			return false;
		}
	}

	@Override
	public int unreadNotifications(String userId, Instant since) {
		Integer count = jdbc.queryForObject(
			"select count(*) from notifications where user_id = ? and is_read = false and created_at >= ?",
			Integer.class, userId, Timestamp.from(since));
		return count == null ? 0 : count;
	}

	@Override
	public List<DigestItem> communityPosts(String userId, Instant since, int limit) {
		// Channel posts and moments stay out: channels can be narrower than the community, and a
		// moment may have expired by the time the e-mail is read.
		return jdbc.query("""
				select p.id, p.content, c.name, p.reactions + p.comments as score from posts p
				join community_members m on m.community_id = p.community_id and m.user_id = ?
				join communities c on c.id = p.community_id
				where p.author_id <> ? and p.status = 'ACTIVE' and p.deleted_at is null and p.visibility = 'PUBLIC'
				and p.channel_id is null and p.expires_at is null and p.repost_of is null and p.created_at >= ?
				and %s
				order by score desc, p.created_at desc limit ?""".formatted(NOT_HIDDEN.formatted("p.author_id")),
			(rs, row) -> new DigestItem(rs.getString(2), rs.getString(3), "/post/" + rs.getString(1), rs.getInt(4)),
			userId, userId, Timestamp.from(since), userId, userId, userId, limit);
	}

	@Override
	public List<DigestItem> activeGroups(String userId, Instant since, int limit) {
		return jdbc.query("""
				select g.name, g.slug, sum(gp.reactions + gp.comments + 1) as score from group_posts gp
				join group_members gm on gm.group_id = gp.group_id and gm.user_id = ?
				join groups g on g.id = gp.group_id
				where gp.author_id <> ? and gp.status = 'PUBLISHED' and g.status = 'ACTIVE' and gp.created_at >= ?
				and %s
				group by g.name, g.slug
				order by score desc limit ?""".formatted(NOT_HIDDEN.formatted("gp.author_id")),
			(rs, row) -> new DigestItem("Conversas novas em " + rs.getString(1), null, "/grupos/" + rs.getString(2), rs.getInt(3)),
			userId, userId, Timestamp.from(since), userId, userId, userId, limit);
	}

	@Override
	public List<DigestItem> matchingJobs(String userId, Instant since, int limit) {
		return jdbc.query("""
				select j.id, j.title, j.company from jobs j
				where j.status = 'OPEN' and j.posted_at >= ? and j.posted_by <> ?
				and exists (select 1 from job_skills js join user_skills us on lower(js.skill) = lower(us.skill)
					where js.job_id = j.id and us.user_id = ?)
				and not exists (select 1 from job_applications a where a.job_id = j.id and a.user_id = ?)
				order by j.posted_at desc limit ?""",
			(rs, row) -> new DigestItem(rs.getString(2), rs.getString(3), "/vagas/" + rs.getString(1), 0),
			Timestamp.from(since), userId, userId, userId, limit);
	}

	@Override
	public List<AttendingEvent> attendingEvents(String userId) {
		return jdbc.query("""
				select e.id, e.title, e.date, e.time from events e
				join event_attendees a on a.event_id = e.id and a.user_id = ?
				where e.sanitized_at is null""",
			(rs, row) -> new AttendingEvent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
			userId);
	}
}
