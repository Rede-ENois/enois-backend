package com.socialcoisa.rede.network.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.network.domain.StoragePlacement;

/**
 * O catálogo do Storage em JDBC direto.
 *
 * <p>JDBC e não JPA pelo mesmo motivo das demais persistências do módulo: as operações têm
 * semântica própria que um {@code save()} esconderia. As duas que importam:</p>
 *
 * <ul>
 * <li>{@code createPending} é <b>inserir-ou-ler</b>, não inserir: o mesmo cliente repetindo o
 * upload (retry de rede, multipart retomado) dobra a chave única e a resposta tem de ser o objeto
 * que já existe, não um 500. O {@code on duplicate key update id = id} é o no-op explícito — não
 * atualiza {@code updated_at}, senão um retry pareceria modificação do objeto.</li>
 * <li>{@code markStored} e {@code markDeleted} são transições condicionais no {@code where}: só
 * saem do estado em que a transição faz sentido. Um upload que termina depois de o objeto ter
 * sido lapidado não pode ressuscitá-lo ({@code where state = 'PENDING'}).</li>
 * </ul>
 */
@Repository
class StorageCatalogPersistenceAdapter implements StorageCatalogRepository {

	private static final String OBJECT_COLUMNS = """
		id, bucket, object_key, content_type, bytes, sha256, state, created_by, created_at, updated_at, deleted_at
		""";

	private static final String SELECT_BY_KEY = "select " + OBJECT_COLUMNS
		+ " from network_storage_objects where bucket = ? and object_key = ?";

	private static final String SELECT_BY_ID = "select " + OBJECT_COLUMNS
		+ " from network_storage_objects where id = ?";

	private static final String INSERT_PENDING = """
		insert into network_storage_objects (id, bucket, object_key, content_type, state, created_by, created_at, updated_at)
		values (?, ?, ?, ?, 'PENDING', ?, ?, ?)
		on duplicate key update id = id
		""";

	/** Só o PENDING vira STORED: cópia que chega depois da lápide não ressuscita o objeto. */
	private static final String MARK_STORED = """
		update network_storage_objects
		set state = 'STORED', bytes = ?, sha256 = ?, updated_at = ?
		where id = ? and state = 'PENDING'
		""";

	/** Lápide idempotente: repetir em DELETED não reescreve a data da primeira remoção. */
	private static final String MARK_DELETED = """
		update network_storage_objects
		set state = 'DELETED', deleted_at = ?, updated_at = ?
		where id = ? and state <> 'DELETED'
		""";

	/**
	 * Listagem por prefixo e paginação por chave (cursor), não por OFFSET: com {@code object_key} no
	 * índice, "as próximas N depois desta chave" é uma leitura de árvore; OFFSET reescreveria as
	 * linhas anteriores a cada página.
	 */
	private static final String LIST = """
		select %s from network_storage_objects
		where bucket = ? and object_key like ? and object_key > ? and state <> 'DELETED'
		order by object_key asc
		limit ?
		""".formatted(OBJECT_COLUMNS);

	private static final String UPSERT_PLACEMENT = """
		insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
		values (?, ?, ?, ?, ?, ?, ?)
		on duplicate key update node_id = values(node_id), bytes = values(bytes), sha256 = values(sha256),
		  state = values(state), verified_at = values(verified_at)
		""";

	private static final String PLACEMENT_COLUMNS = "object_id, replica, node_id, bytes, sha256, state, verified_at";

	private static final String SELECT_PLACEMENTS = "select " + PLACEMENT_COLUMNS
		+ " from network_storage_placements where object_id = ? order by replica asc";

	private static final String SELECT_PLACEMENT_ON = "select " + PLACEMENT_COLUMNS
		+ " from network_storage_placements where object_id = ? and node_id = ? and state = 'OK' order by replica asc";

	private static final String SELECT_NODE_PLACEMENTS = "select " + PLACEMENT_COLUMNS
		+ " from network_storage_placements where node_id = ? order by verified_at desc";

	private final JdbcTemplate jdbc;

	StorageCatalogPersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<StorageObject> find(String bucket, String key) {
		return jdbc.query(SELECT_BY_KEY, OBJECT_MAPPER, bucket, key).stream().findFirst();
	}

	@Override
	public Optional<StorageObject> findById(String id) {
		return jdbc.query(SELECT_BY_ID, OBJECT_MAPPER, id).stream().findFirst();
	}

	@Override
	public StorageObject createPending(String id, String bucket, String key, String contentType, String createdBy,
			Instant now) {
		Timestamp stamp = Timestamp.from(now);
		jdbc.update(INSERT_PENDING, id, bucket, key, contentType, createdBy, stamp, stamp);
		// Ler depois do insert-ou-noop é o que garante a mesma resposta nos dois caminhos: o objeto
		// recém-criado e o que já existia.
		return find(bucket, key).orElseThrow(() -> new IllegalStateException(
			"objeto " + bucket + "/" + key + " invisivel logo apos o insert"));
	}

	@Override
	public boolean markStored(String id, long bytes, String sha256, Instant now) {
		return jdbc.update(MARK_STORED, bytes, sha256, Timestamp.from(now), id) == 1;
	}

	@Override
	public boolean markDeleted(String id, Instant now) {
		Timestamp stamp = Timestamp.from(now);
		return jdbc.update(MARK_DELETED, stamp, stamp, id) == 1;
	}

	@Override
	public List<StorageObject> list(String bucket, String prefix, String afterKey, int limit) {
		// O LIKE é prefixo literal: o '%' do cliente seria curinga e listaria outra coisa.
		String pattern = escapeLike(prefix == null ? "" : prefix) + "%";
		String after = afterKey == null ? "" : afterKey;
		return jdbc.query(LIST, OBJECT_MAPPER, bucket, pattern, after, limit);
	}

	@Override
	public void savePlacement(StoragePlacement placement) {
		jdbc.update(UPSERT_PLACEMENT, placement.objectId(), placement.replica(), placement.nodeId(),
			placement.bytes(), placement.sha256(), placement.state().name(),
			Timestamp.from(placement.verifiedAt()));
	}

	@Override
	public List<StoragePlacement> placements(String objectId) {
		return jdbc.query(SELECT_PLACEMENTS, PLACEMENT_MAPPER, objectId);
	}

	@Override
	public Optional<StoragePlacement> placementOn(String objectId, String nodeId) {
		return jdbc.query(SELECT_PLACEMENT_ON, PLACEMENT_MAPPER, objectId, nodeId).stream().findFirst();
	}

	@Override
	public List<StoragePlacement> placementsOnNode(String nodeId) {
		return jdbc.query(SELECT_NODE_PLACEMENTS, PLACEMENT_MAPPER, nodeId);
	}

	/** {@code %}, {@code _} e {@code \} do prefixo são literais numa chave S3, não padrão de busca. */
	static String escapeLike(String raw) {
		StringBuilder escaped = new StringBuilder(raw.length() + 8);
		for (char c : raw.toCharArray()) {
			if (c == '%' || c == '_' || c == '\\') {
				escaped.append('\\');
			}
			escaped.append(c);
		}
		return escaped.toString();
	}

	private static final RowMapper<StorageObject> OBJECT_MAPPER = (ResultSet rs, int rowNum) -> new StorageObject(
		rs.getString("id"), rs.getString("bucket"), rs.getString("object_key"), rs.getString("content_type"),
		nullableLong(rs, "bytes"), rs.getString("sha256"), StorageObjectState.valueOf(rs.getString("state")),
		rs.getString("created_by"), instant(rs, "created_at"), instant(rs, "updated_at"),
		instant(rs, "deleted_at"));

	private static final RowMapper<StoragePlacement> PLACEMENT_MAPPER = (ResultSet rs, int rowNum) ->
		new StoragePlacement(rs.getString("object_id"), rs.getInt("replica"), rs.getString("node_id"),
			rs.getLong("bytes"), rs.getString("sha256"), StoragePlacement.State.valueOf(rs.getString("state")),
			instant(rs, "verified_at"));

	private static Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp stamp = rs.getTimestamp(column);
		return stamp == null ? null : stamp.toInstant();
	}
}