package com.socialcoisa.rede.moderation.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
	 * Candidatas: peça de imagem, existente, não apagada e sem linha no ledger. O join com
	 * `media_assets` é o que garante que só se checa o que ainda está no ar; uma folha só entra
	 * quando ainda pertence ao render de um documento (o pedido guarda a chave das folhas), porque
	 * folha solta não leva a documento nenhum. O pedido é perguntado por EXISTS, não por join: como o
	 * pedido tem uma linha só, o join com a mídia só casaria quem é documento, nunca a folha.
	 */
	private static final String UNCHECKED = """
		select a.id
		from media_assets a
		left join media_safety_checks c on c.media_id = a.id
		where c.media_id is null
		  and a.created_at >= ?
		  and a.delete_status = 'NONE'
		  and a.mime_type in ('image/jpeg','image/png','image/gif','image/webp','image/avif')
		  and (not (a.object_key like 'paginas/%')
		    or exists (select 1 from media_render_jobs r where r.page_keys like concat('%', a.object_key, '%')))
		order by a.created_at desc
		limit ?
		""";

	/**
	 * Donos com peça sem veredito: imagem no ar sem linha, ou folha de PDF no ar sem linha (a folha
	 * é o asset irmão do documento, gravado pelo render das páginas).
	 */
	private static final String UNCHECKED_OWNERS = """
		select distinct d.owner_id
		from media_assets d
		left join media_safety_checks c on c.media_id = d.id
		where d.owner_id is not null
		  and d.created_at >= ?
		  and d.delete_status = 'NONE'
		  and (
		    (d.mime_type in ('image/jpeg','image/png','image/gif','image/webp','image/avif') and c.media_id is null)
		    or d.mime_type in ('application/pdf','image/png')
		  )
		order by d.owner_id
		limit ?
		""";

	/**
	 * Peças com violação gravada na janela: a imagem pela própria linha, o documento pela linha de
	 * qualquer folha sua (o ledger de folha não leva a documento por outro caminho). Se a peça ainda
	 * está no ar é o serviço quem decide — pedido de remoção não é remoção.
	 */
	private static final String VIOLATORS = """
		select distinct a.id
		from media_assets a
		where (exists (select 1 from media_safety_checks c where c.media_id = a.id
		        and c.verdict in ('EXPLICIT','SEXUAL_MINOR') and c.checked_at >= ?)
		   or exists (select 1 from media_safety_checks c join media_assets f on f.id = c.media_id
		        where c.verdict in ('EXPLICIT','SEXUAL_MINOR') and c.checked_at >= ?
		          and exists (select 1 from media_render_jobs r where r.media_id = a.id
		            and r.page_keys like concat('%', f.object_key, '%'))))
		and a.object_key not like 'paginas/%'
		order by a.created_at desc
		limit ?
		""";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate leituraFresca;

	JdbcMediaSafetyCheckAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
		this.jdbc = jdbc;
		// Transação própria: fora de uma, o pool empresta conexão em autocommit e a leitura cai numa
		// transação alheia que nunca vê o que a varredura acabou de gravar.
		this.leituraFresca = new TransactionTemplate(transactions);
		this.leituraFresca.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
	public List<String> findUncheckedOwnersSince(Instant since, int limit) {
		return jdbc.queryForList(UNCHECKED_OWNERS, String.class, Timestamp.from(since), Math.max(1, limit));
	}

	@Override
	public List<String> findViolatorsSince(Instant since, int limit) {
		return leituraFresca.execute(status -> jdbc.queryForList(VIOLATORS, String.class, Timestamp.from(since),
			Timestamp.from(since), Math.max(1, limit)));
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
