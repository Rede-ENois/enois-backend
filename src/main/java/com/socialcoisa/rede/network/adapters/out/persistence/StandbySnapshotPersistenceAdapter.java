package com.socialcoisa.rede.network.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.StandbySnapshotRepository;

/**
 * Cópias do banco nos nós de reserva (V364), em JDBC direto.
 *
 * <p>JDBC pelo mesmo motivo do termo da autoridade: a transição é condicional. Marcar
 * {@code SYNCING} não pode apagar a cópia completa anterior — se a transferência cair no meio, a
 * linha tem de continuar contando a última cópia boa. O {@code update} só toca as colunas da
 * tentativa, e isso é uma instrução, não uma leitura seguida de escrita.</p>
 */
@Repository
class StandbySnapshotPersistenceAdapter implements StandbySnapshotRepository {

	private static final String COLUMNS = """
		node_id, authority_id, state, term, started_at, completed_at, bytes, byte_start, sha256,
		failure_reason, requested_by
		""";

	/**
	 * Início da tentativa. `state = 'SYNCING'` e as colunas da tentativa sobrescrevem as da
	 * anterior; `completed_at`, `bytes` e `sha256` são PRESERVADOS — são a prova da última cópia
	 * completa, e apagá-los no início faria uma queda no meio aparentar "nunca houve cópia".
	 */
	private static final String START_SYNC = """
		insert into network_standby_snapshots
			(node_id, authority_id, state, term, started_at, byte_start, failure_reason, requested_by)
		values (?, ?, 'SYNCING', ?, ?, ?, null, ?)
		on duplicate key update
			state = 'SYNCING', term = values(term), started_at = values(started_at),
			byte_start = values(byte_start), failure_reason = null, requested_by = values(requested_by)
		""";

	private static final String MARK_READY = """
		update network_standby_snapshots
		set state = 'READY', completed_at = ?, bytes = ?, sha256 = ?, term = ?
		where node_id = ? and authority_id = ?
		""";

	/**
	 * Falhou: só o motivo e o estado mudam. A cópia anterior fica marcada {@code FAILED}, mas as
	 * colunas dela ({@code completed_at}, {@code bytes}, {@code sha256}) continuam na linha — é o
	 * painel que mostra o que existe e o motivo da última falha.
	 */
	private static final String MARK_FAILED = """
		update network_standby_snapshots
		set state = 'FAILED', failure_reason = ?
		where node_id = ? and authority_id = ?
		""";

	private final JdbcTemplate jdbc;

	StandbySnapshotPersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Snapshot> find(String nodeId, String authorityId) {
		return jdbc.query("select " + COLUMNS + " from network_standby_snapshots where node_id = ? and authority_id = ?",
			(rs, i) -> map(rs), nodeId, authorityId).stream().findFirst();
	}

	@Override
	public void startSync(String nodeId, String authorityId, int term, long byteStart, String requestedBy,
			Instant now) {
		jdbc.update(START_SYNC, nodeId, authorityId, term, Timestamp.from(now), byteStart, requestedBy);
	}

	@Override
	public void markReady(String nodeId, String authorityId, long bytes, String sha256, int term, Instant now) {
		jdbc.update(MARK_READY, Timestamp.from(now), bytes, sha256, term, nodeId, authorityId);
	}

	@Override
	public void markFailed(String nodeId, String authorityId, String reason, Instant now) {
		String trimmed = reason == null ? null : reason.length() > 200 ? reason.substring(0, 200) : reason;
		jdbc.update(MARK_FAILED, trimmed, nodeId, authorityId);
	}

	@Override
	public int readyCount(String nodeId) {
		Integer count = jdbc.queryForObject("""
			select count(*) from network_standby_snapshots
			where node_id = ? and state = 'READY' and sha256 is not null
			""", Integer.class, nodeId);
		return count == null ? 0 : count;
	}

	@Override
	public boolean anyReadySince(Instant since) {
		Integer count = jdbc.queryForObject("""
			select count(*) from network_standby_snapshots
			where state = 'READY' and sha256 is not null and completed_at >= ?
			""", Integer.class, Timestamp.from(since));
		return count != null && count > 0;
	}

	@Override
	public List<Snapshot> all() {
		return jdbc.query("select " + COLUMNS
			+ " from network_standby_snapshots order by coalesce(completed_at, started_at) desc", (rs, i) -> map(rs));
	}

	private static Snapshot map(java.sql.ResultSet rs) throws java.sql.SQLException {
		Timestamp completed = rs.getTimestamp("completed_at");
		Long bytes = (Long) rs.getObject("bytes", Long.class);
		return new Snapshot(rs.getString("node_id"), rs.getString("authority_id"), rs.getString("state"),
			rs.getInt("term"), rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
			completed == null ? null : completed.toInstant(), bytes, rs.getLong("byte_start"),
			rs.getString("sha256"), rs.getString("failure_reason"), rs.getString("requested_by"));
	}
}