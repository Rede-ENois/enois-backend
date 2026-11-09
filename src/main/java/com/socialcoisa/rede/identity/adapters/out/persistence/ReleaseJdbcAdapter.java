package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.identity.application.port.out.ReleaseRepository;
import com.socialcoisa.rede.identity.domain.ReleasePolicy;
import com.socialcoisa.rede.identity.domain.ReleaseRound;
import com.socialcoisa.rede.identity.domain.ReleaseRoundState;

/**
 * Fila de liberacao e rodadas, em SQL direto.
 *
 * <p>A prioridade sai de UMA consulta so, ordenada no banco: {@code users.reputation} decrescente,
 * depois {@code created_at} crescente (quem esta aqui ha mais tempo), depois {@code id} para o
 * desempate ser sempre o mesmo. A credibilidade e a coluna que o worker de reputacao ja mantem —
 * nao existe nota paralela.</p>
 *
 * <p>Ficam fora da fila: contas removidas, contas com sancao ativa, quem ja tem o selo, quem ja
 * recebeu convite e quem esta com verificacao em andamento.</p>
 */
@Component
class ReleaseJdbcAdapter implements ReleaseRepository {

	private static final String SETTINGS_ID = "default";

	private static final String CANDIDATES = """
		select u.id, u.reputation, u.created_at
		from users u
		where u.reputation >= ?
		  and u.username not like 'removed-%'
		  and u.email not like 'removed+%@enois.invalid'
		  and not exists (select 1 from didit_verification_grants g where g.user_id = u.id)
		  and not exists (select 1 from user_sanctions s
		                  where s.user_id = u.id and s.status = 'ACTIVE')
		  and not exists (select 1 from identity_verifications v
		                  where v.user_id = u.id and v.state = 'VERIFIED')
		  and not exists (select 1 from identity_verifications v
		                  where v.user_id = u.id and v.state = 'PENDING')
		order by u.reputation desc, u.created_at asc, u.id asc
		limit ?
		""";

	private final JdbcTemplate jdbc;

	ReleaseJdbcAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public ReleasePolicy policy(Instant now) {
		List<ReleasePolicy> found = jdbc.query("""
			select users_per_round, interval_minutes, min_reputation, reserved_quota_percent,
			       max_simultaneous, paused, next_round_at
			from didit_release_settings where id = ?
			""", (rs, row) -> new ReleasePolicy(rs.getInt("users_per_round"), rs.getInt("interval_minutes"),
				rs.getInt("min_reputation"), rs.getInt("reserved_quota_percent"),
				rs.getInt("max_simultaneous"), rs.getBoolean("paused"), instant(rs, "next_round_at")),
			SETTINGS_ID);
		return found.isEmpty() ? ReleasePolicy.defaults(now) : found.getFirst();
	}

	@Override
	@Transactional
	public void savePolicy(ReleasePolicy policy) {
		ReleasePolicy safe = policy.sanitized();
		int updated = jdbc.update("""
			update didit_release_settings
			set users_per_round = ?, interval_minutes = ?, min_reputation = ?,
			    reserved_quota_percent = ?, max_simultaneous = ?, paused = ?, next_round_at = ?, updated_at = ?
			where id = ?
			""", safe.usersPerRound(), safe.intervalMinutes(), safe.minReputation(),
			safe.reservedQuotaPercent(), safe.maxSimultaneous(), safe.paused(),
			safe.nextRoundAt() == null ? null : Timestamp.from(safe.nextRoundAt()),
			Timestamp.from(Instant.now()), SETTINGS_ID);
		if (updated == 0) {
			// Primeira gravacao: nasce da politica vigente, avaliada pelo banco (nao pelo codigo).
			jdbc.update("""
				insert into didit_release_settings (id, users_per_round, interval_minutes, min_reputation,
				    reserved_quota_percent, max_simultaneous, paused, next_round_at, updated_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", SETTINGS_ID, safe.usersPerRound(), safe.intervalMinutes(), safe.minReputation(),
				safe.reservedQuotaPercent(), safe.maxSimultaneous(), safe.paused(),
				safe.nextRoundAt() == null ? null : Timestamp.from(safe.nextRoundAt()),
				Timestamp.from(Instant.now()));
		}
	}

	@Override
	public List<ReleaseRound> rounds(int limit) {
		return jdbc.query("""
			select id, executed_at, eligible_count, released_count, min_reputation, rank_start, rank_end,
			       state, reserved_capacity_used, note
			from didit_release_rounds order by executed_at desc, id desc limit ?
			""", (rs, row) -> round(rs), limit);
	}

	@Override
	public ReleaseRound round(String id) {
		List<ReleaseRound> found = jdbc.query("""
			select id, executed_at, eligible_count, released_count, min_reputation, rank_start, rank_end,
			       state, reserved_capacity_used, note
			from didit_release_rounds where id = ?
			""", (rs, row) -> round(rs), id);
		return found.isEmpty() ? null : found.getFirst();
	}

	@Override
	@Transactional
	public void saveRound(ReleaseRound round) {
		int updated = jdbc.update("""
			update didit_release_rounds
			set executed_at = ?, eligible_count = ?, released_count = ?, min_reputation = ?,
			    rank_start = ?, rank_end = ?, state = ?, reserved_capacity_used = ?, note = ?
			where id = ?
			""", Timestamp.from(round.executedAt()), round.eligibleCount(), round.releasedCount(),
			round.minReputation(), round.rankStart(), round.rankEnd(), round.state().name(),
			round.reservedCapacityUsed(), round.note(), round.id());
		if (updated == 0) {
			jdbc.update("""
				insert into didit_release_rounds (id, executed_at, eligible_count, released_count,
				    min_reputation, rank_start, rank_end, state, reserved_capacity_used, note)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", round.id(), Timestamp.from(round.executedAt()), round.eligibleCount(),
				round.releasedCount(), round.minReputation(), round.rankStart(), round.rankEnd(),
				round.state().name(), round.reservedCapacityUsed(), round.note());
		}
	}

	@Override
	public List<Candidate> candidates(int minReputation, int limit) {
		return jdbc.query(CANDIDATES, (rs, row) -> new Candidate(rs.getString("id"), rs.getInt("reputation"),
			instant(rs, "created_at")), minReputation, limit);
	}

	@Override
	public int inFlight() {
		Integer count = jdbc.queryForObject("""
			select count(*) from identity_verifications
			where provider = 'didit' and state = 'PENDING'
			""", Integer.class);
		return count == null ? 0 : count;
	}

	@Override
	public long totalUsers() {
		Long count = jdbc.queryForObject("""
			select count(*) from users where username not like 'removed-%'
			""", Long.class);
		return count == null ? 0 : count;
	}

	@Override
	public long releasedUsers() {
		Long count = jdbc.queryForObject("""
			select count(*) from didit_verification_grants where revoked_at is null
			""", Long.class);
		return count == null ? 0 : count;
	}

	private static ReleaseRound round(ResultSet rs) throws SQLException {
		return new ReleaseRound(rs.getString("id"), instant(rs, "executed_at"), rs.getInt("eligible_count"),
			rs.getInt("released_count"), rs.getInt("min_reputation"), rs.getInt("rank_start"),
			rs.getInt("rank_end"), ReleaseRoundState.valueOf(rs.getString("state")),
			rs.getInt("reserved_capacity_used"), rs.getString("note"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}
}