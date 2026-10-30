package com.socialcoisa.rede.moderation.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.moderation.application.port.out.MediaSafetyCheckRepository;
import com.socialcoisa.rede.moderation.domain.MediaSafetyCheck;
import com.socialcoisa.rede.moderation.domain.MediaSafetyVerdict;

/**
 * Ledger em JDBC direto: é histórico que só cresce e é lido por varredura, o mesmo desenho do
 * {@code JdbcRiskAssessmentWriter}. O upsert por {@code media_id} é o que evita reprocessar.
 */
@Component
class JdbcMediaSafetyCheckAdapter implements MediaSafetyCheckRepository {

	private static final String UPSERT = """
		insert into media_safety_checks
			(media_id, owner_id, verdict, severity, confidence, reason, model, checked_at)
		values (?, ?, ?, ?, ?, ?, ?, ?)
		on duplicate key update
			verdict = values(verdict), severity = values(severity), confidence = values(confidence),
			reason = values(reason), model = values(model), checked_at = values(checked_at)
		""";

	private static final String COLUMNS =
		"media_id, owner_id, verdict, severity, confidence, reason, model, checked_at";

	/**
	 * Candidatas: mídia de imagem, existente, não apagada e sem linha no ledger. O join com
	 * `media_assets` é o que garante que só se checa o que ainda está no ar.
	 */
	private static final String UNCHECKED = """
		select a.id
		from media_assets a
		left join media_safety_checks c on c.media_id = a.id
		where c.media_id is null
		  and a.created_at >= ?
		  and a.delete_status = 'NONE'
		  and a.mime_type in ('image/jpeg','image/png','image/gif','image/webp','image/avif')
		order by a.created_at desc
		limit ?
		""";

	private final JdbcTemplate jdbc;

	JdbcMediaSafetyCheckAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<MediaSafetyCheck> findById(String mediaId) {
		return jdbc.query("select " + COLUMNS + " from media_safety_checks where media_id = ?",
			(rs, row) -> toDomain(rs), mediaId).stream().findFirst();
	}

	@Override
	public MediaSafetyCheck save(MediaSafetyCheck check) {
		jdbc.update(UPSERT, check.mediaId(), check.ownerId(), check.verdict().name(), check.severity(),
			check.confidence(), check.reason(), check.model(), Timestamp.from(check.checkedAt()));
		return check;
	}

	@Override
	public List<String> findUncheckedMediaIdsSince(Instant since, int limit) {
		return jdbc.queryForList(UNCHECKED, String.class, Timestamp.from(since), Math.max(1, limit));
	}

	@Override
	public List<MediaSafetyCheck> findViolationsSince(Instant since, int limit) {
		return jdbc.query("select " + COLUMNS + " from media_safety_checks "
			+ "where verdict in ('EXPLICIT','SEXUAL_MINOR') and checked_at >= ? order by checked_at desc limit ?",
			(rs, row) -> toDomain(rs), Timestamp.from(since), Math.max(1, limit));
	}

	private MediaSafetyCheck toDomain(ResultSet rs) throws SQLException {
		return new MediaSafetyCheck(rs.getString("media_id"), rs.getString("owner_id"),
			MediaSafetyVerdict.valueOf(rs.getString("verdict")), rs.getString("severity"),
			rs.getDouble("confidence"), rs.getString("reason"), rs.getString("model"),
			rs.getTimestamp("checked_at").toInstant());
	}
}
