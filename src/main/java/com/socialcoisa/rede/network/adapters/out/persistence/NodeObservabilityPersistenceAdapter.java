package com.socialcoisa.rede.network.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.NodeObservabilityRepository;

/**
 * Amostras e logs do nó em JDBC direto: o teto é uma instrução de delete por chave, e o upsert do
 * minuto precisa substituir em vez de duplicar. `save()` esconderia as duas intenções.
 */
@Repository
class NodeObservabilityPersistenceAdapter implements NodeObservabilityRepository {

	/**
	 * O mesmo minuto é substituído: dois heartbeats no mesmo minuto não abrem dois pontos, e o
	 * segundo (mais novo) é o que vale. A chave é o instante do minuto, não o do heartbeat, para a
	 * série ter passo regular.
	 */
	private static final String UPSERT_SAMPLE = """
		insert into network_node_samples (node_id, sampled_at, cpu_percent, memory_mb, disk_free_mb,
		  disk_iops, disk_latency_ms, temperature_c, running_tasks, queue_depth, mode, activity)
		values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		on duplicate key update cpu_percent = values(cpu_percent), memory_mb = values(memory_mb),
		  disk_free_mb = values(disk_free_mb), disk_iops = values(disk_iops),
		  disk_latency_ms = values(disk_latency_ms), temperature_c = values(temperature_c),
		  running_tasks = values(running_tasks),
		  queue_depth = values(queue_depth), mode = values(mode), activity = values(activity)
		""";

	private static final String SAMPLE_COLUMNS = "node_id, sampled_at, cpu_percent, memory_mb, disk_free_mb, "
		+ "disk_iops, disk_latency_ms, temperature_c, running_tasks, queue_depth, mode, activity";

	private static final String LIST_SAMPLES = "select " + SAMPLE_COLUMNS
		+ " from network_node_samples where node_id = ? order by sampled_at desc limit ?";

	/** Delete pelos mais antigos além do teto — o que sobra é a janela recente. */
	private static final String TRIM_SAMPLES = """
		delete from network_node_samples where node_id = ? and sampled_at not in (
		  select sampled_at from (
		    select sampled_at from network_node_samples where node_id = ? order by sampled_at desc limit ?
		  ) keep_rows
		)
		""";

	/**
	 * Soma na faixa da hora corrente. O instante é truncado em hora: é a régua da coluna, e truncar
	 * aqui (e não confiar no chamador) garante que duas tarefas do mesmo minuto caem na MESMA linha.
	 */
	private static final String UPSERT_LATENCY = """
		insert into network_task_latency_buckets (node_id, metrics_hour, bucket_upper_ms, samples)
		values (?, ?, ?, 1)
		on duplicate key update samples = samples + 1
		""";

	private static final String UPSERT_RETRY = """
		insert into network_task_latency_buckets (node_id, metrics_hour, bucket_upper_ms, samples, retries)
		values (?, ?, ?, 0, 1)
		on duplicate key update retries = retries + 1
		""";

	private static final String SUM_LATENCY = "select bucket_upper_ms, sum(samples) from network_task_latency_buckets "
		+ "where node_id = ? and metrics_hour >= ? and metrics_hour <= ? group by bucket_upper_ms";

	private static final String SUM_RETRIES = "select bucket_upper_ms, sum(retries) "
		+ "from network_task_latency_buckets where node_id = ? and metrics_hour >= ? and metrics_hour <= ? "
		+ "group by bucket_upper_ms";

	private static final String INSERT_LOG = "insert into network_node_logs (id, node_id, logged_at, level, message) "
		+ "values (?, ?, ?, ?, ?)";

	private static final String LIST_LOGS = "select id, node_id, logged_at, level, message from network_node_logs "
		+ "where node_id = ? order by logged_at desc, id desc limit ?";

	private static final String TRIM_LOGS = """
		delete from network_node_logs where node_id = ? and id not in (
		  select id from (
		    select id from network_node_logs where node_id = ? order by logged_at desc, id desc limit ?
		  ) keep_rows
		)
		""";

	private static final RowMapper<Sample> SAMPLE_MAPPER = (rs, row) -> new Sample(rs.getString("node_id"),
		rs.getTimestamp("sampled_at").toInstant(), nullableDouble(rs, "cpu_percent"), nullableInt(rs, "memory_mb"),
		nullableLong(rs, "disk_free_mb"), nullableInt(rs, "running_tasks"), nullableInt(rs, "queue_depth"),
		rs.getString("mode"), rs.getString("activity"), nullableDouble(rs, "disk_iops"),
		nullableDouble(rs, "disk_latency_ms"), rs.getBigDecimal("temperature_c"));

	private static final RowMapper<LogLine> LOG_MAPPER = (rs, row) -> new LogLine(rs.getString("id"),
		rs.getString("node_id"), rs.getTimestamp("logged_at").toInstant(), rs.getString("level"),
		rs.getString("message"));

	private final JdbcTemplate jdbc;

	NodeObservabilityPersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void saveSample(Sample sample) {
		jdbc.update(UPSERT_SAMPLE, sample.nodeId(), Timestamp.from(sample.sampledAt()), sample.cpuPercent(),
			sample.memoryMb(), sample.diskFreeMb(), sample.diskIops(), sample.diskLatencyMs(), sample.temperatureC(),
			sample.runningTasks(),
			sample.queueDepth(), sample.mode(), sample.activity());
	}

	@Override
	public void trimSamples(String nodeId, int keep) {
		jdbc.update(TRIM_SAMPLES, nodeId, nodeId, keep);
	}

	@Override
	public List<Sample> samples(String nodeId, int limit) {
		// O gráfico desenha do mais antigo para o mais novo: a consulta ordena desc (o LIMIT pega os
		// recentes) e a lista volta invertida.
		List<Sample> newestFirst = jdbc.query(LIST_SAMPLES, SAMPLE_MAPPER, nodeId, limit);
		return newestFirst.reversed();
	}

	@Override
	public void addLatencySample(String nodeId, Instant at, int bucketUpperMs) {
		jdbc.update(UPSERT_LATENCY, nodeId, Timestamp.from(at.truncatedTo(java.time.temporal.ChronoUnit.HOURS)),
			bucketUpperMs);
	}

	@Override
	public void addRetrySample(String nodeId, Instant at, long durationMs) {
		jdbc.update(UPSERT_RETRY, nodeId, Timestamp.from(at.truncatedTo(java.time.temporal.ChronoUnit.HOURS)),
			com.socialcoisa.rede.network.domain.TaskLatency.bucketOf(durationMs));
	}

	@Override
	public java.util.Map<Integer, Integer> retrySamples(String nodeId, Instant now, int hours) {
		Instant from = now.minusSeconds(hours * 3600L).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
		java.util.Map<Integer, Integer> samples = new java.util.TreeMap<>();
		jdbc.query(SUM_RETRIES, rs -> {
			samples.put(rs.getInt(1), rs.getInt(2));
		}, nodeId, Timestamp.from(from), Timestamp.from(now));
		return samples;
	}

	@Override
	public java.util.Map<Integer, Integer> latencySamples(String nodeId, Instant now, int hours) {
		Instant from = now.minusSeconds(hours * 3600L).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
		java.util.Map<Integer, Integer> samples = new java.util.TreeMap<>();
		jdbc.query(SUM_LATENCY, rs -> {
			samples.put(rs.getInt(1), rs.getInt(2));
		}, nodeId, Timestamp.from(from), Timestamp.from(now));
		return samples;
	}

	@Override
	public void appendLog(LogLine line) {
		jdbc.update(INSERT_LOG, line.id(), line.nodeId(), Timestamp.from(line.loggedAt()), line.level(),
			line.message());
	}

	@Override
	public void trimLogs(String nodeId, int keep) {
		jdbc.update(TRIM_LOGS, nodeId, nodeId, keep);
	}

	@Override
	public List<LogLine> logs(String nodeId, int limit) {
		return jdbc.query(LIST_LOGS, LOG_MAPPER, nodeId, limit);
	}

	private static Double nullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		double value = rs.getDouble(column);
		return rs.wasNull() ? null : value;
	}

	private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}
}