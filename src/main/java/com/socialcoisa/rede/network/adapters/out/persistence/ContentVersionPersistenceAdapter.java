package com.socialcoisa.rede.network.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.ContentVersionRepository;
import com.socialcoisa.rede.network.domain.ContentKind;
import com.socialcoisa.rede.network.domain.ContentVersion;

/**
 * Versões de conteúdo público e snapshots de nó (V277), em JDBC direto.
 *
 * <p>JDBC e não JPA porque as duas escritas são condicionais por natureza: a versão só sobe se a
 * linha ainda estiver na versão esperada, e o snapshot é um upsert. Em JPA isso viraria leitura +
 * comparação + escrita com janela entre elas; aqui é uma instrução, e o banco decide.</p>
 */
@Repository
class ContentVersionPersistenceAdapter implements ContentVersionRepository {

	private static final String SELECT_ONE = """
		select content_type, content_id, version, content_hash, updated_at
		from network_content_versions where content_type = ? and content_id = ?
		""";

	/** Primeira versão nasce em 1; as seguintes só sobem se a linha ainda estiver na esperada. */
	private static final String INSERT_FIRST = """
		insert into network_content_versions (content_type, content_id, version, content_hash, updated_at)
		values (?, ?, ?, ?, ?)
		""";

	private static final String UPDATE_IF_EXPECTED = """
		update network_content_versions set version = ?, content_hash = ?, updated_at = ?
		where content_type = ? and content_id = ? and version = ?
		""";

	private static final String SELECT_SNAPSHOT = """
		select node_id, content_type, content_id, source_version, content_hash, synced_at
		from network_content_snapshots where node_id = ? and content_type = ? and content_id = ?
		""";

	private static final String UPSERT_SNAPSHOT = """
		insert into network_content_snapshots
			(node_id, content_type, content_id, source_version, content_hash, synced_at)
		values (?, ?, ?, ?, ?, ?)
		on duplicate key update source_version = values(source_version),
			content_hash = values(content_hash), synced_at = values(synced_at)
		""";

	/**
	 * Atraso do nó: agregados que a origem tem e o nó não tem na mesma versão. O {@code left join}
	 * conta também o que o nó nunca baixou — "nunca sincronizou" é o maior atraso possível.
	 */
	private static final String COUNT_BEHIND = """
		select count(*) from network_content_versions v
		left join network_content_snapshots s
			on s.content_type = v.content_type and s.content_id = v.content_id and s.node_id = ?
		where v.content_type = ? and (s.source_version is null or s.source_version < v.version)
		""";

	private final JdbcTemplate jdbc;

	ContentVersionPersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<ContentVersion> find(ContentKind kind, String contentId) {
		return jdbc.query(SELECT_ONE, (rs, row) -> map(rs), kind.name(), contentId).stream().findFirst();
	}

	@Override
	public boolean save(ContentVersion version, long expectedVersion) {
		Timestamp at = Timestamp.from(version.updatedAt());
		if (expectedVersion == 0) {
			// Primeira versão: o insert é a própria trava. Duas instâncias cunhando a versão 1 ao
			// mesmo tempo — a segunda bate na PK e não escreve.
			try {
				return jdbc.update(INSERT_FIRST, version.kind().name(), version.contentId(), version.version(),
					version.contentHash(), at) == 1;
			}
			catch (org.springframework.dao.DuplicateKeyException alreadyThere) {
				return false;
			}
		}
		return jdbc.update(UPDATE_IF_EXPECTED, version.version(), version.contentHash(), at, version.kind().name(),
			version.contentId(), expectedVersion) == 1;
	}

	@Override
	public Optional<NodeSnapshot> snapshot(String nodeId, ContentKind kind, String contentId) {
		return jdbc.query(SELECT_SNAPSHOT,
			(rs, row) -> new NodeSnapshot(rs.getString("node_id"), ContentKind.valueOf(rs.getString("content_type")),
				rs.getString("content_id"), rs.getLong("source_version"), rs.getString("content_hash"),
				rs.getTimestamp("synced_at").toInstant()),
			nodeId, kind.name(), contentId).stream().findFirst();
	}

	@Override
	public void recordSnapshot(String nodeId, ContentKind kind, String contentId, long sourceVersion,
			String contentHash, Instant now) {
		jdbc.update(UPSERT_SNAPSHOT, nodeId, kind.name(), contentId, sourceVersion, contentHash,
			Timestamp.from(now));
	}

	@Override
	public long countBehind(String nodeId, ContentKind kind) {
		Long count = jdbc.queryForObject(COUNT_BEHIND, Long.class, nodeId, kind.name());
		return count == null ? 0 : count;
	}

	private static ContentVersion map(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new ContentVersion(ContentKind.valueOf(rs.getString("content_type")), rs.getString("content_id"),
			rs.getLong("version"), rs.getString("content_hash"), rs.getTimestamp("updated_at").toInstant());
	}
}
