package com.socialcoisa.rede.network.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.MediaJobRepository;
import com.socialcoisa.rede.network.domain.MediaJobStatus;
import com.socialcoisa.rede.network.domain.MediaTranscodeJob;

/**
 * Jobs de transcode delegado (V295), em JDBC direto.
 *
 * <p>JDBC porque as transições são condicionais por estado e a comparação é o ponto: {@code
 * markVerified} só grava se a linha ainda não estiver VERIFIED, então duas tentativas que chegam
 * juntas não verificam duas vezes — a segunda atualiza zero linhas.</p>
 */
@Repository
class MediaJobPersistenceAdapter implements MediaJobRepository {

	private static final String COLUMNS = "media_id, source_key, mime_type, source_bytes, source_sha256, "
		+ "result_key, result_bytes, result_sha256, status, task_id, last_error, created_at, verified_at";

	private static final String SELECT_BY_MEDIA = "select " + COLUMNS
		+ " from network_media_jobs where media_id = ?";

	private static final String SELECT_BY_TASK = "select " + COLUMNS + " from network_media_jobs where task_id = ?";

	private static final String INSERT = """
		insert into network_media_jobs (media_id, source_key, mime_type, source_bytes, source_sha256, result_key,
			status, task_id, created_at)
		values (?, ?, ?, ?, ?, ?, ?, ?, ?)
		""";

	private static final String MEASURE = """
		update network_media_jobs set result_bytes = ?, result_sha256 = ?
		where media_id = ? and status <> 'VERIFIED'
		""";

	private static final String VERIFY = """
		update network_media_jobs set status = 'VERIFIED', result_bytes = ?, result_sha256 = ?, verified_at = ?,
			last_error = null
		where media_id = ? and status <> 'VERIFIED'
		""";

	private static final String REJECT = """
		update network_media_jobs set status = 'REJECTED', last_error = ?
		where media_id = ? and status <> 'VERIFIED'
		""";

	private static final String RECORD_FAILURE = """
		update network_media_jobs set last_error = ? where media_id = ? and status = 'PENDING'
		""";

	private static final String LIST_RECENT = "select " + COLUMNS
		+ " from network_media_jobs order by created_at desc limit ?";

	private static final String COUNT_BY_STATUS = "select count(*) from network_media_jobs where status = ?";

	private final JdbcTemplate jdbc;

	MediaJobPersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<MediaTranscodeJob> findByMediaId(String mediaId) {
		return jdbc.query(SELECT_BY_MEDIA, (rs, row) -> map(rs), mediaId).stream().findFirst();
	}

	@Override
	public Optional<MediaTranscodeJob> findByTaskId(String taskId) {
		return jdbc.query(SELECT_BY_TASK, (rs, row) -> map(rs), taskId).stream().findFirst();
	}

	@Override
	public void create(MediaTranscodeJob job) {
		jdbc.update(INSERT, job.mediaId(), job.sourceKey(), job.mimeType(), job.sourceBytes(), job.sourceSha256(),
			job.resultKey(), job.status().name(), job.taskId(), Timestamp.from(job.createdAt()));
	}

	@Override
	public int markMeasured(String mediaId, long resultBytes, String resultSha256) {
		return jdbc.update(MEASURE, resultBytes, resultSha256, mediaId);
	}

	@Override
	public int markVerified(String mediaId, long resultBytes, String resultSha256, Instant now) {
		return jdbc.update(VERIFY, resultBytes, resultSha256, Timestamp.from(now), mediaId);
	}

	@Override
	public int markRejected(String mediaId, String reason, Instant now) {
		return jdbc.update(REJECT, truncate(reason), mediaId);
	}

	@Override
	public int recordFailure(String mediaId, String reason) {
		return jdbc.update(RECORD_FAILURE, truncate(reason), mediaId);
	}

	@Override
	public List<MediaTranscodeJob> listRecent(int limit) {
		return jdbc.query(LIST_RECENT, (rs, row) -> map(rs), limit);
	}

	@Override
	public long countByStatus(MediaJobStatus status) {
		Long count = jdbc.queryForObject(COUNT_BY_STATUS, Long.class, status.name());
		return count == null ? 0 : count;
	}

	private static MediaTranscodeJob map(ResultSet rs) throws SQLException {
		Timestamp verified = rs.getTimestamp("verified_at");
		Long resultBytes = rs.getObject("result_bytes") == null ? null : rs.getLong("result_bytes");
		return new MediaTranscodeJob(rs.getString("media_id"), rs.getString("source_key"),
			rs.getString("mime_type"), rs.getLong("source_bytes"), rs.getString("source_sha256"),
			rs.getString("result_key"), resultBytes, rs.getString("result_sha256"),
			MediaJobStatus.valueOf(rs.getString("status")), rs.getString("task_id"), rs.getString("last_error"),
			rs.getTimestamp("created_at").toInstant(), verified == null ? null : verified.toInstant());
	}

	private static String truncate(String text) {
		if (text == null) {
			return null;
		}
		return text.length() > 500 ? text.substring(0, 500) : text;
	}
}