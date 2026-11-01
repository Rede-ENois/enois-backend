package com.socialcoisa.rede.studypairs.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.studypairs.application.port.out.StudyPairRepository;
import com.socialcoisa.rede.studypairs.domain.PopularGoal;
import com.socialcoisa.rede.studypairs.domain.StudyCheckin;
import com.socialcoisa.rede.studypairs.domain.StudyPair;
import com.socialcoisa.rede.studypairs.domain.StudyPairRequest;

/**
 * Plain SQL on purpose (same choice as the two-factor adapter): three small tables, and the match
 * needs a conditional claim ({@code update ... where status = 'OPEN'}) plus a group-by that JPA
 * entities would only wrap.
 */
@Repository
class StudyPairPersistenceAdapter implements StudyPairRepository {

	private static final String REQUEST_COLUMNS = "id, user_id, goal_key, goal_text, topic, status, created_at";
	private static final String PAIR_COLUMNS =
		"id, user_a, user_b, goal_key, goal_text, topic, status, started_at, ended_at";

	private final JdbcTemplate jdbc;

	StudyPairPersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void lockUser(String userId) {
		// The row is only a handle: a session always has a `users` row, so the lock never comes
		// back empty and two concurrent requests for the same person line up here.
		jdbc.queryForList("select id from users where id = ? for update", String.class, userId);
	}

	@Override
	public void saveRequest(StudyPairRequest r) {
		jdbc.update("insert into study_pair_requests (" + REQUEST_COLUMNS + ", open_user_id)"
			+ " values (?, ?, ?, ?, ?, ?, ?, ?)",
			r.id(), r.userId(), r.goalKey(), r.goalText(), r.topic(), r.status().name(), ts(r.createdAt()),
			r.status() == StudyPairRequest.Status.OPEN ? r.userId() : null);
	}

	@Override
	public Optional<StudyPairRequest> findOpenRequest(String userId) {
		return jdbc.query("select " + REQUEST_COLUMNS + " from study_pair_requests where user_id = ? and status = 'OPEN'"
				+ " order by created_at", (rs, n) -> request(rs), userId)
			.stream().findFirst();
	}

	@Override
	public List<StudyPairRequest> findOpenByGoal(String goalKey, int limit) {
		return jdbc.query("select " + REQUEST_COLUMNS + " from study_pair_requests where goal_key = ? and status = 'OPEN'"
				+ " order by created_at, id limit ?", (rs, n) -> request(rs), goalKey, limit);
	}

	@Override
	public List<StudyPairRequest> searchOpenRequests(String term, int limit) {
		// goal_key is stored already folded (lowercase, no accents, single dashes). The topic is stored
		// as the taxonomy constant ("QUALIDADE_VIDA"), so the underscore is folded to a dash before the
		// comparison and "qualidade" still finds it.
		String like = "%" + term + "%";
		return jdbc.query("select " + REQUEST_COLUMNS + " from study_pair_requests where status = 'OPEN'"
				+ " and (goal_key like ? or replace(lower(coalesce(topic, '')), '_', '-') like ?)"
				+ " order by created_at, id limit ?", (rs, n) -> request(rs), like, like, limit);
	}

	@Override
	public boolean closeOpenRequest(String requestId, StudyPairRequest.Status status) {
		return jdbc.update("update study_pair_requests set status = ?, open_user_id = null where id = ? and status = 'OPEN'",
			status.name(), requestId) == 1;
	}

	@Override
	public void reopenRequest(String requestId) {
		jdbc.update("update study_pair_requests set status = 'OPEN', open_user_id = user_id"
			+ " where id = ? and status = 'MATCHED'", requestId);
	}

	@Override
	public void savePair(StudyPair p) {
		jdbc.update("insert into study_pairs (" + PAIR_COLUMNS + ", active_pair_key) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
			p.id(), p.userA(), p.userB(), p.goalKey(), p.goalText(), p.topic(), p.status().name(), ts(p.startedAt()),
			p.endedAt() == null ? null : ts(p.endedAt()), activePairKey(p));
	}

	@Override
	public Optional<StudyPair> findPair(String pairId) {
		return jdbc.query("select " + PAIR_COLUMNS + " from study_pairs where id = ?", (rs, n) -> pair(rs), pairId)
			.stream().findFirst();
	}

	@Override
	public List<StudyPair> findActivePairs(String userId) {
		return jdbc.query("select " + PAIR_COLUMNS + " from study_pairs where status = 'ACTIVE'"
			+ " and (user_a = ? or user_b = ?) order by started_at", (rs, n) -> pair(rs), userId, userId);
	}

	@Override
	public List<StudyPair> findAllActivePairs() {
		return jdbc.query("select " + PAIR_COLUMNS + " from study_pairs where status = 'ACTIVE'", (rs, n) -> pair(rs));
	}

	@Override
	public boolean endPair(String pairId, Instant endedAt) {
		return jdbc.update("update study_pairs set status = 'ENDED', ended_at = ?, active_pair_key = null"
			+ " where id = ? and status = 'ACTIVE'", ts(endedAt), pairId) == 1;
	}

	@Override
	public Optional<StudyCheckin> findCheckin(String pairId, String userId, String weekKey) {
		return jdbc.query("select pair_id, user_id, week_key, note, created_at, updated_at from study_checkins"
				+ " where pair_id = ? and user_id = ? and week_key = ?", (rs, n) -> checkin(rs), pairId, userId, weekKey)
			.stream().findFirst();
	}

	@Override
	public void insertCheckin(StudyCheckin c) {
		jdbc.update("insert into study_checkins (pair_id, user_id, week_key, note, created_at, updated_at)"
			+ " values (?, ?, ?, ?, ?, ?)", c.pairId(), c.userId(), c.weekKey(), c.note(), ts(c.createdAt()),
			ts(c.updatedAt()));
	}

	@Override
	public void updateCheckinNote(String pairId, String userId, String weekKey, String note, Instant updatedAt) {
		jdbc.update("update study_checkins set note = ?, updated_at = ? where pair_id = ? and user_id = ? and week_key = ?",
			note, ts(updatedAt), pairId, userId, weekKey);
	}

	@Override
	public List<StudyCheckin> findCheckins(String pairId, Collection<String> weekKeys) {
		if (weekKeys.isEmpty()) {
			return List.of();
		}
		List<Object> args = new ArrayList<>();
		args.add(pairId);
		args.addAll(weekKeys);
		String placeholders = String.join(", ", Collections.nCopies(weekKeys.size(), "?"));
		return jdbc.query("select pair_id, user_id, week_key, note, created_at, updated_at from study_checkins"
			+ " where pair_id = ? and week_key in (" + placeholders + ") order by week_key desc, created_at",
			(rs, n) -> checkin(rs), args.toArray());
	}

	@Override
	public List<PopularGoal> popularOpenGoals(int limit) {
		return jdbc.query("select goal_key, min(goal_text) as goal_text, max(topic) as topic, count(*) as open_requests"
				+ " from study_pair_requests where status = 'OPEN' group by goal_key"
				+ " order by open_requests desc, goal_key limit ?",
			(rs, n) -> new PopularGoal(rs.getString("goal_key"), rs.getString("goal_text"), rs.getString("topic"),
				rs.getInt("open_requests")),
			limit);
	}

	@Override
	public boolean claimReminder(String pairId, String userId, String weekKey, Instant at) {
		try {
			jdbc.update("insert into study_pair_reminders (pair_id, user_id, week_key, created_at) values (?, ?, ?, ?)",
				pairId, userId, weekKey, ts(at));
			return true;
		}
		catch (DuplicateKeyException alreadyClaimed) {
			return false;
		}
	}

	private static String activePairKey(StudyPair p) {
		if (p.status() != StudyPair.Status.ACTIVE) {
			return null;
		}
		String a = p.userA();
		String b = p.userB();
		return a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
	}

	private static StudyPairRequest request(ResultSet rs) throws SQLException {
		return new StudyPairRequest(rs.getString("id"), rs.getString("user_id"), rs.getString("goal_key"),
			rs.getString("goal_text"), rs.getString("topic"), StudyPairRequest.Status.valueOf(rs.getString("status")),
			instant(rs, "created_at"));
	}

	private static StudyPair pair(ResultSet rs) throws SQLException {
		return new StudyPair(rs.getString("id"), rs.getString("user_a"), rs.getString("user_b"),
			rs.getString("goal_key"), rs.getString("goal_text"), rs.getString("topic"),
			StudyPair.Status.valueOf(rs.getString("status")), instant(rs, "started_at"), instant(rs, "ended_at"));
	}

	private static StudyCheckin checkin(ResultSet rs) throws SQLException {
		return new StudyCheckin(rs.getString("pair_id"), rs.getString("user_id"), rs.getString("week_key"),
			rs.getString("note"), instant(rs, "created_at"), instant(rs, "updated_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private static Timestamp ts(Instant value) {
		return Timestamp.from(value);
	}
}
