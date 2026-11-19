package com.socialcoisa.rede.network.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.NetworkJoinRequestRepository;
import com.socialcoisa.rede.network.domain.NetworkJoinRequest;

/**
 * Inscrições para servir um nó em JDBC direto, pelas mesmas razões do catálogo do Storage: as
 * escritas têm semântica condicional que um {@code save()} esconderia. Decidir só vale sobre
 * {@code PENDING}; consumir o link só vale sobre {@code reveal_consumed_at is null}. Essas condições
 * são a garantia contra duas requisições concorrentes, não um detalhe de implementação.
 */
@Repository
class NetworkJoinRequestPersistenceAdapter implements NetworkJoinRequestRepository {

	private static final String COLUMNS = "id, full_name, email, rg, cpf, motivation, term_version, accepted_at, "
		+ "status, profiles, decided_by, decided_at, decision_note, reveal_token_hash, reveal_expires_at, "
		+ "reveal_consumed_at, email_sent_at, created_at, updated_at";

	private static final String INSERT = """
		insert into network_join_requests (id, full_name, email, rg, cpf, motivation, term_version, accepted_at,
		  status, profiles, decided_by, decided_at, decision_note, reveal_token_hash, reveal_expires_at,
		  reveal_consumed_at, email_sent_at, created_at, updated_at)
		values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		""";

	private static final String SELECT_BY_ID = "select " + COLUMNS + " from network_join_requests where id = ?";

	private static final String SELECT_PENDING_DUPLICATE = "select " + COLUMNS
		+ " from network_join_requests where status = 'PENDING' and (email = ? or cpf = ?) limit 1";

	private static final String SELECT_BY_REVEAL = "select " + COLUMNS
		+ " from network_join_requests where reveal_token_hash = ?";

	private static final String SELECT_BY_REVEAL_LOCKED = SELECT_BY_REVEAL + " for update";

	private static final String LIST_ALL = "select " + COLUMNS
		+ " from network_join_requests order by created_at desc limit ?";

	private static final String LIST_BY_STATUS = "select " + COLUMNS
		+ " from network_join_requests where status = ? order by created_at desc limit ?";

	private static final String DECIDE = """
		update network_join_requests set status = ?, profiles = ?, decided_by = ?, decided_at = ?,
		  decision_note = ?, reveal_token_hash = ?, reveal_expires_at = ?, updated_at = ?
		where id = ? and status = 'PENDING'
		""";

	private static final String ROTATE_REVEAL = """
		update network_join_requests set reveal_token_hash = ?, reveal_expires_at = ?, updated_at = ?
		where id = ? and status = 'APPROVED' and reveal_consumed_at is null
		""";

	private static final String CONSUME_REVEAL = """
		update network_join_requests set reveal_consumed_at = ?, updated_at = ?
		where id = ? and reveal_consumed_at is null
		""";

	private static final String MARK_EMAIL_SENT = "update network_join_requests set email_sent_at = ? where id = ?";

	private static final RowMapper<NetworkJoinRequest> MAPPER = NetworkJoinRequestPersistenceAdapter::map;

	private final JdbcTemplate jdbc;

	NetworkJoinRequestPersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void insert(NetworkJoinRequest r) {
		jdbc.update(INSERT, r.id(), r.fullName(), r.email(), r.rg(), r.cpf(), r.motivation(), r.termVersion(),
			timestamp(r.acceptedAt()), r.status().name(), r.profiles(), r.decidedBy(), timestamp(r.decidedAt()),
			r.decisionNote(), r.revealTokenHash(), timestamp(r.revealExpiresAt()),
			timestamp(r.revealConsumedAt()), timestamp(r.emailSentAt()), timestamp(r.createdAt()),
			timestamp(r.updatedAt()));
	}

	@Override
	public Optional<NetworkJoinRequest> findById(String id) {
		return jdbc.query(SELECT_BY_ID, MAPPER, id).stream().findFirst();
	}

	@Override
	public Optional<NetworkJoinRequest> findPendingByEmailOrCpf(String email, String cpf) {
		return jdbc.query(SELECT_PENDING_DUPLICATE, MAPPER, email, cpf).stream().findFirst();
	}

	@Override
	public List<NetworkJoinRequest> list(NetworkJoinRequest.Status status, int limit) {
		return status == null
			? jdbc.query(LIST_ALL, MAPPER, limit)
			: jdbc.query(LIST_BY_STATUS, MAPPER, status.name(), limit);
	}

	@Override
	public Optional<NetworkJoinRequest> findByRevealHashForUpdate(String revealHash) {
		return jdbc.query(SELECT_BY_REVEAL_LOCKED, MAPPER, revealHash).stream().findFirst();
	}

	@Override
	public Optional<NetworkJoinRequest> findByRevealHash(String revealHash) {
		return jdbc.query(SELECT_BY_REVEAL, MAPPER, revealHash).stream().findFirst();
	}

	@Override
	public boolean decide(String id, NetworkJoinRequest.Status status, String profiles, String decidedBy,
			String note, String revealHash, Instant revealExpiresAt, Instant now) {
		return jdbc.update(DECIDE, status.name(), profiles, decidedBy, timestamp(now), note, revealHash,
			timestamp(revealExpiresAt), timestamp(now), id) == 1;
	}

	@Override
	public boolean rotateReveal(String id, String newHash, Instant expiresAt, Instant now) {
		return jdbc.update(ROTATE_REVEAL, newHash, timestamp(expiresAt), timestamp(now), id) == 1;
	}

	@Override
	public boolean consumeReveal(String id, Instant now) {
		return jdbc.update(CONSUME_REVEAL, timestamp(now), timestamp(now), id) == 1;
	}

	@Override
	public void markEmailSent(String id, Instant now) {
		jdbc.update(MARK_EMAIL_SENT, timestamp(now), id);
	}

	private static Timestamp timestamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	private static NetworkJoinRequest map(ResultSet rs, int row) throws SQLException {
		return new NetworkJoinRequest(rs.getString("id"), rs.getString("full_name"), rs.getString("email"),
			rs.getString("rg"), rs.getString("cpf"), rs.getString("motivation"), rs.getString("term_version"),
			instant(rs, "accepted_at"), NetworkJoinRequest.Status.valueOf(rs.getString("status")),
			rs.getString("profiles"), rs.getString("decided_by"), instant(rs, "decided_at"),
			rs.getString("decision_note"), rs.getString("reveal_token_hash"), instant(rs, "reveal_expires_at"),
			instant(rs, "reveal_consumed_at"), instant(rs, "email_sent_at"), instant(rs, "created_at"),
			instant(rs, "updated_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}
}