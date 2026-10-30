package com.socialcoisa.rede.moderation.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.moderation.application.port.out.LinkSafetyCheckRepository;
import com.socialcoisa.rede.moderation.domain.LinkSafetyCheck;
import com.socialcoisa.rede.moderation.domain.LinkSafetyVerdict;

/**
 * Ledger em JDBC direto, igual ao da mídia: histórico que só cresce e é lido por varredura. A chave
 * é o hash da URL, então o mesmo destino postado de novo não é visitado outra vez.
 */
@Component
class JdbcLinkSafetyCheckAdapter implements LinkSafetyCheckRepository {

	private static final String UPSERT = """
		insert into link_safety_checks
			(url_hash, url, owner_id, verdict, severity, confidence, reason, model, checked_at)
		values (?, ?, ?, ?, ?, ?, ?, ?, ?)
		on duplicate key update
			owner_id = values(owner_id), verdict = values(verdict), severity = values(severity),
			confidence = values(confidence), reason = values(reason), model = values(model),
			checked_at = values(checked_at)
		""";

	private static final String COLUMNS =
		"url_hash, url, owner_id, verdict, severity, confidence, reason, model, checked_at";

	private final JdbcTemplate jdbc;

	JdbcLinkSafetyCheckAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<LinkSafetyCheck> findByUrl(String url) {
		return jdbc.query("select " + COLUMNS + " from link_safety_checks where url_hash = ?",
			(rs, row) -> toDomain(rs), LinkSafetyCheck.hashOf(url)).stream().findFirst();
	}

	@Override
	public LinkSafetyCheck save(LinkSafetyCheck check) {
		jdbc.update(UPSERT, check.urlHash(), check.url(), check.ownerId(), check.verdict().name(),
			check.severity(), check.confidence(), check.reason(), check.model(), Timestamp.from(check.checkedAt()));
		return check;
	}

	@Override
	public List<LinkSafetyCheck> findViolationsSince(Instant since, int limit) {
		return jdbc.query("select " + COLUMNS + " from link_safety_checks "
			+ "where verdict in ('EXPLICIT','SUSPICIOUS') and checked_at >= ? order by checked_at desc limit ?",
			(rs, row) -> toDomain(rs), Timestamp.from(since), Math.max(1, limit));
	}

	private LinkSafetyCheck toDomain(ResultSet rs) throws SQLException {
		return new LinkSafetyCheck(rs.getString("url_hash"), rs.getString("url"), rs.getString("owner_id"),
			LinkSafetyVerdict.valueOf(rs.getString("verdict")), rs.getString("severity"),
			rs.getDouble("confidence"), rs.getString("reason"), rs.getString("model"),
			rs.getTimestamp("checked_at").toInstant());
	}
}
