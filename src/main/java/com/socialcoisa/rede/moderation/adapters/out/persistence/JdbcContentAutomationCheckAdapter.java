package com.socialcoisa.rede.moderation.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.moderation.application.port.out.ContentAutomationCheckRepository;
import com.socialcoisa.rede.moderation.domain.ContentAutomationCheck;
import com.socialcoisa.rede.moderation.domain.ContentAutomationCheck.ReviewStatus;
import com.socialcoisa.rede.moderation.domain.ContentAutomationVerdict;

/**
 * Ledger em JDBC direto: é histórico que só cresce e é lido por varredura, o mesmo desenho do
 * {@code JdbcMediaSafetyCheckAdapter}. O upsert por {@code post_id} é o que evita reprocessar.
 */
@Component
class JdbcContentAutomationCheckAdapter implements ContentAutomationCheckRepository {

	private static final String UPSERT = """
		insert into content_automation_checks
			(post_id, author_id, verdict, confidence, reason, model, checked_at, review_status)
		values (?, ?, ?, ?, ?, ?, ?, ?)
		on duplicate key update
			verdict = values(verdict), confidence = values(confidence), reason = values(reason),
			model = values(model), checked_at = values(checked_at), review_status = values(review_status)
		""";

	private static final String COLUMNS =
		"post_id, author_id, verdict, confidence, reason, model, checked_at, review_status";

	/**
	 * Candidatos: posts ativos da janela recente sem linha no ledger. O left join é o que impede a
	 * varredura de reler o que já foi avaliado — incluindo o que foi registrado como humano.
	 */
	private static final String UNCHECKED = """
		select p.id
		from posts p
		left join content_automation_checks c on c.post_id = p.id
		where c.post_id is null
		  and p.status = 'ACTIVE'
		  and p.created_at >= ?
		order by p.created_at desc
		limit ?
		""";

	private final JdbcTemplate jdbc;

	JdbcContentAutomationCheckAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<ContentAutomationCheck> findByPost(String postId) {
		return jdbc.query("select " + COLUMNS + " from content_automation_checks where post_id = ?",
			(rs, row) -> toDomain(rs), postId).stream().findFirst();
	}

	@Override
	public ContentAutomationCheck save(ContentAutomationCheck check) {
		jdbc.update(UPSERT, check.postId(), check.authorId(), check.verdict().name(), check.confidence(),
			check.reason(), check.model(), Timestamp.from(check.checkedAt()), check.status().name());
		return check;
	}

	@Override
	public void updateStatus(String postId, ReviewStatus status) {
		jdbc.update("update content_automation_checks set review_status = ? where post_id = ?",
			status.name(), postId);
	}

	@Override
	public List<ContentAutomationCheck> findPending(int limit) {
		// A fila é o que está REALMENTE fora do ar: o join com `posts` evita oferecer ao moderador um
		// caso que outro caminho de revisão já resolveu (denúncia arquivada, post apagado pelo autor).
		return jdbc.query("select c.post_id, c.author_id, c.verdict, c.confidence, c.reason, c.model, "
			+ "c.checked_at, c.review_status from content_automation_checks c "
			+ "join posts p on p.id = c.post_id "
			+ "where c.review_status = 'PENDING' and p.status = 'QUARANTINED' "
			+ "order by c.checked_at desc limit ?",
			(rs, row) -> toDomain(rs), Math.max(1, limit));
	}

	@Override
	public List<String> findUncheckedPostIdsSince(Instant since, int limit) {
		return jdbc.queryForList(UNCHECKED, String.class, Timestamp.from(since), Math.max(1, limit));
	}

	private static ContentAutomationCheck toDomain(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new ContentAutomationCheck(
			rs.getString("post_id"),
			rs.getString("author_id"),
			ContentAutomationVerdict.valueOf(rs.getString("verdict")),
			rs.getDouble("confidence"),
			rs.getString("reason"),
			rs.getString("model"),
			rs.getTimestamp("checked_at").toInstant(),
			ReviewStatus.valueOf(rs.getString("review_status")));
	}
}