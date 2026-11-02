package com.socialcoisa.rede.onboarding.adapters.out.persistence;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.onboarding.application.port.out.StarterPackReader;

/**
 * The ranking lives in SQL: "matches first, then popular" is one `order by`, so the fallback for a
 * person with no topic is the same query. An empty list becomes a value no row has ('') because
 * `in ()` is not valid SQL.
 */
@Repository
class JdbcStarterPackAdapter implements StarterPackReader {

	private static final String COMMUNITIES_SQL = """
		select c.id, c.name, c.description, c.topic, c.avatar, count(m.user_id) as member_count
		from communities c
		left join community_members m on m.community_id = c.id
		where c.id not in (select community_id from community_members where user_id = :user)
		group by c.id, c.name, c.description, c.topic, c.avatar
		order by case when c.topic in (:topics) then 0 else 1 end, member_count desc, c.id
		limit :limit
		""";

	private static final String GROUPS_SQL = """
		select g.id, g.slug, g.name, g.description, g.topic, g.avatar, g.member_count
		from groups g
		where g.status = 'ACTIVE' and g.join_mode = 'OPEN'
		  and g.id not in (select group_id from group_members where user_id = :user)
		order by case when g.topic in (:topics) then 0 else 1 end, g.member_count desc, g.id
		limit :limit
		""";

	// Liked topics are stored as a comma list; wrapping both sides in commas matches whole items.
	private static final String PEOPLE_SQL = """
		select u.id
		from users u
		left join user_preferences p on p.user_id = u.id
		where u.id <> :user
		  and u.email_verified_at is not null
		  and (u.birth_date is null or u.birth_date <= :adultCutoff)
		  and exists (select 1 from user_credentials uc where uc.user_id = u.id)
		  and not exists (select 1 from user_sanctions s where s.user_id = u.id and s.status = 'ACTIVE'
		    and s.type in ('SUSPENDED', 'BAN', 'PERMABAN'))
		  and not exists (select 1 from user_connections uc2 where uc2.status in ('PENDING', 'ACCEPTED')
		    and ((uc2.requester_user_id = :user and uc2.receiver_user_id = u.id)
		      or (uc2.receiver_user_id = :user and uc2.requester_user_id = u.id)))
		  and not exists (select 1 from user_blocks b
		    where (b.blocker_id = :user and b.blocked_id = u.id) or (b.blocker_id = u.id and b.blocked_id = :user))
		  and not exists (select 1 from user_mutes mu where mu.muter_id = :user and mu.muted_id = u.id)
		order by (select count(*) from user_skills k where k.user_id = u.id and lower(k.skill) in (:skills))
		  + %s desc, u.reputation desc, u.created_at desc
		limit :limit
		""";

	private final NamedParameterJdbcTemplate jdbc;

	JdbcStarterPackAdapter(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<CommunityCandidate> communities(String userId, List<String> topics, int limit) {
		return jdbc.query(COMMUNITIES_SQL, params(userId, limit).addValue("topics", orNone(topics)),
			(rs, row) -> new CommunityCandidate(rs.getString("id"), rs.getString("name"), rs.getString("description"),
				rs.getString("topic"), rs.getString("avatar"), rs.getInt("member_count")));
	}

	@Override
	public List<GroupCandidate> openGroups(String userId, List<String> topics, int limit) {
		return jdbc.query(GROUPS_SQL, params(userId, limit).addValue("topics", orNone(topics)),
			(rs, row) -> new GroupCandidate(rs.getString("id"), rs.getString("slug"), rs.getString("name"),
				rs.getString("description"), rs.getString("topic"), rs.getString("avatar"),
				rs.getInt("member_count")));
	}

	@Override
	public List<String> people(String userId, List<String> topics, List<String> skills, LocalDate adultBornOnOrBefore,
			int limit) {
		MapSqlParameterSource params = params(userId, limit)
			.addValue("skills", orNone(skills))
			.addValue("adultCutoff", Date.valueOf(adultBornOnOrBefore));
		StringBuilder topicScore = new StringBuilder("0");
		for (int i = 0; i < topics.size(); i++) {
			topicScore.append(" + case when concat(',', coalesce(p.liked_topics, ''), ',') like :topic").append(i)
				.append(" then 1 else 0 end");
			params.addValue("topic" + i, "%," + topics.get(i) + ",%");
		}
		return jdbc.queryForList(PEOPLE_SQL.formatted(topicScore), params, String.class);
	}

	private static MapSqlParameterSource params(String userId, int limit) {
		return new MapSqlParameterSource("user", userId).addValue("limit", limit);
	}

	private static List<String> orNone(List<String> values) {
		return values.isEmpty() ? List.of("") : values;
	}
}
