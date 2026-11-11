package com.socialcoisa.rede.shared.broadcast;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tabela de sinais entre instâncias (V276). JDBC direto, como o resto do fan-out.
 *
 * <p>O desenho tem três detalhes que não são acaso:</p>
 * <ul>
 * <li><b>A emissão participa da transação de quem emitiu.</b> O sinal é gravado pela mesma
 * transação do efeito (o commit da mensagem, o commit da edição), então não existe janela em que a
 * outra instância é avisada de algo que não commitou — nem o contrário, efeito commitado sem
 * aviso. Quem chama só publica depois do commit, pelo {@code InstanceBroadcaster}.</li>
 * <li><b>A leitura é por instância e não consome.</b> Três instâncias leem o mesmo sinal; por isso
 * não há recibo nem claim, e a posição de leitura vive em {@code network_instance_readers}, não em
 * uma coluna da tabela de sinais. Consumir aqui seria entregar a um só.</li>
 * <li><b>A retenção apaga por idade.</b> Sinal antigo não serve para nada: broadcast perdido é
 * coberto pelo TTL do cache e pelo reconnect do EventSource. Se a tabela não crescesse limitada,
 * ela viraria o problema que veio resolver.</li>
 * </ul>
 */
@Component
public class InstanceSignalStore {

	private static final String INSERT = """
		insert into network_instance_signals
			(id, origin_instance, signal_type, target, payload, created_at)
		values (?, ?, ?, ?, ?, ?)
		""";

	/** Leitura por posição: o que está depois do que esta instância já entregou e não é dela. */
	private static final String SELECT_PENDING = """
		select seq, id, origin_instance, signal_type, target, payload, created_at
		from network_instance_signals
		where seq > ? and origin_instance <> ?
		order by seq asc
		limit ?
		""";

	private static final String UPSERT_READER = """
		insert into network_instance_readers (instance_id, started_at, last_seen_at, last_seq, last_signal_at)
		values (?, ?, ?, ?, ?)
		on duplicate key update last_seen_at = values(last_seen_at),
			last_seq = greatest(last_seq, values(last_seq)),
			last_signal_at = coalesce(values(last_signal_at), last_signal_at)
		""";

	private static final String MAX_SEQ = """
		select coalesce(max(seq), 0) from network_instance_signals
		""";

	private static final String PURGE = """
		delete from network_instance_signals where created_at < ? limit ?
		""";

	private static final String PURGE_READERS = """
		delete from network_instance_readers where last_seen_at < ?
		""";

	private final JdbcTemplate jdbc;

	InstanceSignalStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	void insert(InstanceSignal signal) {
		jdbc.update(INSERT, signal.id(), signal.originInstance(), signal.type(), signal.target(),
			signal.payload(), Timestamp.from(signal.createdAt()));
	}

	List<InstanceSignal> pending(String instanceId, long afterSeq, int limit) {
		return jdbc.query(SELECT_PENDING,
			(rs, row) -> new InstanceSignal(rs.getLong("seq"), rs.getString("id"),
				rs.getString("origin_instance"), rs.getString("signal_type"), rs.getString("target"),
				rs.getString("payload"), rs.getTimestamp("created_at").toInstant()),
			afterSeq, instanceId, limit);
	}

	/** Presença e posição de leitura desta instância. */
	void heartbeatReader(String instanceId, Instant now, long lastSeq, Instant lastSignalAt) {
		Timestamp at = Timestamp.from(now);
		jdbc.update(UPSERT_READER, instanceId, at, at, lastSeq,
			lastSignalAt == null ? null : Timestamp.from(lastSignalAt));
	}

	/** Último sinal da tabela: ponto de partida de uma instância que acabou de subir. */
	long maxSeq() {
		Long max = jdbc.queryForObject(MAX_SEQ, Long.class);
		return max == null ? 0 : max;
	}

	/** @return quantos sinais saíram nesta rodada */
	int purgeOlderThan(Instant threshold, int batchSize) {
		return jdbc.update(PURGE, Timestamp.from(threshold), batchSize);
	}

	int purgeSilentReaders(Instant threshold) {
		return jdbc.update(PURGE_READERS, Timestamp.from(threshold));
	}
}
