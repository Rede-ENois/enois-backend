package com.socialcoisa.rede.network.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeResponsibleRepository;

/** O retrato de quem responde por cada nó — tabela própria, uma linha por nó. */
@Repository
class NetworkNodeResponsiblePersistenceAdapter implements NetworkNodeResponsibleRepository {

	private static final String UPSERT = """
		insert into network_node_responsibles (node_id, full_name, email, source, created_at)
		values (?, ?, ?, ?, ?)
		on duplicate key update full_name = values(full_name), email = values(email),
		  source = values(source)
		""";

	private final JdbcTemplate jdbc;

	NetworkNodeResponsiblePersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void save(String nodeId, String fullName, String email, String source, Instant now) {
		jdbc.update(UPSERT, nodeId, fullName, email, source, Timestamp.from(now));
	}

	@Override
	public Optional<Responsible> find(String nodeId) {
		return jdbc.query("select node_id, full_name, email, source from network_node_responsibles where node_id = ?",
			(rs, row) -> new Responsible(rs.getString("node_id"), rs.getString("full_name"), rs.getString("email"),
				rs.getString("source")),
			nodeId).stream().findFirst();
	}

	@Override
	public Map<String, Responsible> byNodeIds(Collection<String> nodeIds) {
		if (nodeIds == null || nodeIds.isEmpty()) {
			return Map.of();
		}
		String placeholders = String.join(",", java.util.Collections.nCopies(nodeIds.size(), "?"));
		Map<String, Responsible> found = new HashMap<>();
		jdbc.query("select node_id, full_name, email, source from network_node_responsibles where node_id in ("
			+ placeholders + ")", rs -> {
				Responsible responsible = new Responsible(rs.getString("node_id"), rs.getString("full_name"),
					rs.getString("email"), rs.getString("source"));
				found.put(responsible.nodeId(), responsible);
			}, nodeIds.toArray());
		return found;
	}
}