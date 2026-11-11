package com.socialcoisa.rede.network.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.ContentVersionRepository;
import com.socialcoisa.rede.network.domain.ContentKind;
import com.socialcoisa.rede.network.domain.ContentVersion;

/**
 * Versões de conteúdo público e snapshots de nó (V279), em JDBC direto.
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

	private static final String SNAPSHOT_COLUMNS = "node_id, content_type, content_id, source_version, content_hash, "
		+ "synced_at, checked_at, divergence_reason, divergence_at";

	/** As mesmas colunas qualificadas, para as consultas que fazem join. */
	private static final String SNAPSHOT_COLUMNS_S = "s.node_id, s.content_type, s.content_id, s.source_version, "
		+ "s.content_hash, s.synced_at, s.checked_at, s.divergence_reason, s.divergence_at";

	private static final String SELECT_SNAPSHOT = "select " + SNAPSHOT_COLUMNS
		+ " from network_content_snapshots where node_id = ? and content_type = ? and content_id = ?";

	private static final String UPSERT_SNAPSHOT = """
		insert into network_content_snapshots
			(node_id, content_type, content_id, source_version, content_hash, synced_at)
		values (?, ?, ?, ?, ?, ?)
		on duplicate key update source_version = values(source_version),
			content_hash = values(content_hash), synced_at = values(synced_at)
		""";

	/**
	 * A fila da varredura. {@code checked_at is null} primeiro porque snapshot nunca conferido é o
	 * que mais precisa de conferência; depois o mais antigo. O desempate por chave deixa a ordem
	 * estável entre rodadas — sem ele, duas linhas com a mesma hora poderiam alternar e uma delas
	 * nunca seria alcançada.
	 *
	 * <p>O join com {@code network_nodes} não é otimização: é o que impede a fila de travar. Um nó
	 * suspenso ou revogado não é conferido, e sem o filtro as linhas dele (nunca conferidas, logo
	 * no começo da ordem) ocupariam o lote em toda rodada e os nós ativos nunca seriam alcançados.</p>
	 */
	private static final String SELECT_DUE = "select " + SNAPSHOT_COLUMNS_S + """
		 from network_content_snapshots s
		join network_nodes n on n.id = s.node_id
		where n.status = 'ACTIVE'
		order by s.checked_at is not null, s.checked_at, s.node_id, s.content_type, s.content_id
		limit ?
		""";

	/**
	 * A conferência, condicionada ao motivo anterior.
	 *
	 * <p>A comparação precisa tratar nulo como valor: {@code divergence_reason = ?} nunca casa com
	 * nulo, e a linha que estava em dia (motivo nulo) jamais seria atualizada — a varredura rodaria
	 * para sempre sem gravar nada. O igual-nulo do MySQL é {@code <=>}, mas o H2 dos testes não o
	 * conhece; {@code coalesce(..., '')} faz o mesmo em dialeto portátil. O motivo é sempre um dos
	 * códigos fixos desta classe, nunca vazio, então o sentinela não colide com nada.</p>
	 */
	private static final String MARK_CHECKED = """
		update network_content_snapshots
		set checked_at = ?, divergence_reason = ?, divergence_at = ?
		where node_id = ? and content_type = ? and content_id = ? and coalesce(divergence_reason, '') = ?
		""";

	private static final String SELECT_DIVERGENT = "select " + SNAPSHOT_COLUMNS + """
		 from network_content_snapshots
		where divergence_reason is not null
		order by divergence_at desc, node_id, content_type, content_id
		limit ?
		""";

	private static final String COUNT_DIVERGENT = """
		select count(*) from network_content_snapshots where divergence_reason is not null
		""";

	private static final String COUNT_ALL = "select count(*) from network_content_snapshots";

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
		return jdbc.query(SELECT_SNAPSHOT, (rs, row) -> mapSnapshot(rs), nodeId, kind.name(), contentId)
			.stream()
			.findFirst();
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

	@Override
	public List<NodeSnapshot> dueForCheck(int limit) {
		return jdbc.query(SELECT_DUE, (rs, row) -> mapSnapshot(rs), limit);
	}

	@Override
	public int markChecked(String nodeId, ContentKind kind, String contentId, String previousReason, String newReason,
			Instant divergenceAt, Instant now) {
		return jdbc.update(MARK_CHECKED, Timestamp.from(now), newReason,
			divergenceAt == null ? null : Timestamp.from(divergenceAt), nodeId, kind.name(), contentId,
			previousReason == null ? "" : previousReason);
	}

	@Override
	public List<NodeSnapshot> divergent(int limit) {
		return jdbc.query(SELECT_DIVERGENT, (rs, row) -> mapSnapshot(rs), limit);
	}

	@Override
	public long countDivergent() {
		Long count = jdbc.queryForObject(COUNT_DIVERGENT, Long.class);
		return count == null ? 0 : count;
	}

	@Override
	public long countAll() {
		Long count = jdbc.queryForObject(COUNT_ALL, Long.class);
		return count == null ? 0 : count;
	}

	private static NodeSnapshot mapSnapshot(java.sql.ResultSet rs) throws java.sql.SQLException {
		Timestamp checkedAt = rs.getTimestamp("checked_at");
		Timestamp divergenceAt = rs.getTimestamp("divergence_at");
		return new NodeSnapshot(rs.getString("node_id"), ContentKind.valueOf(rs.getString("content_type")),
			rs.getString("content_id"), rs.getLong("source_version"), rs.getString("content_hash"),
			rs.getTimestamp("synced_at").toInstant(), checkedAt == null ? null : checkedAt.toInstant(),
			rs.getString("divergence_reason"), divergenceAt == null ? null : divergenceAt.toInstant());
	}

	private static ContentVersion map(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new ContentVersion(ContentKind.valueOf(rs.getString("content_type")), rs.getString("content_id"),
			rs.getLong("version"), rs.getString("content_hash"), rs.getTimestamp("updated_at").toInstant());
	}
}
