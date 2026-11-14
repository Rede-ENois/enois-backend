package com.socialcoisa.rede.network.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.NetworkRegionRepository;

/**
 * Regiões fora de operação (V304), em JDBC direto.
 *
 * <p>A leitura que o scheduler usa é {@link #outOfService()}, uma linha por região caída — a rede
 * tem dezenas de regiões no máximo, e a consulta roda em toda escolha de nó. JDBC e não JPA pelo
 * mesmo motivo das demais declarações do módulo: a operação é uma instrução com semântica própria
 * ({@code on duplicate key} que NÃO reescreve a data, o oposto do que um {@code save()} de JPA
 * faria), e escondê-la atrás de um agregado tornaria o comportamento de repetição ilegível.</p>
 */
@Repository
class NetworkRegionPersistenceAdapter implements NetworkRegionRepository {

	private static final String SELECT_ALL = """
		select region, declared_by, declared_reason, declared_at from network_region_states
		order by declared_at asc, region asc
		""";

	private static final String SELECT_ONE = """
		select region, declared_by, declared_reason, declared_at from network_region_states
		where region = ?
		""";

	/** Repetir a declaração é no-op: `declared_at` é quando aquela região ENTROU na contingência. */
	private static final String DECLARE = """
		insert into network_region_states (region, declared_by, declared_reason, declared_at)
		values (?, ?, ?, ?)
		on duplicate key update region = region
		""";

	private static final String CLEAR = """
		delete from network_region_states where region = ?
		""";

	private final JdbcTemplate jdbc;

	NetworkRegionPersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<String> outOfService() {
		return jdbc.query(SELECT_ALL, (rs, row) -> rs.getString("region"));
	}

	@Override
	public Optional<RegionRecord> find(String region) {
		return jdbc.query(SELECT_ONE, (rs, row) -> new RegionRecord(rs.getString("region"),
			rs.getString("declared_by"), rs.getString("declared_reason"),
			rs.getTimestamp("declared_at").toInstant()), region)
			.stream()
			.findFirst();
	}

	@Override
	public void declare(String region, String actorId, String reason, Instant now) {
		jdbc.update(DECLARE, region, actorId, reason, Timestamp.from(now));
	}

	@Override
	public boolean clear(String region) {
		return jdbc.update(CLEAR, region) > 0;
	}
}
