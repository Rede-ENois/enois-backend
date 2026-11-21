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
import com.socialcoisa.rede.network.domain.ObjectMetadata;
import com.socialcoisa.rede.network.domain.StorageBucketPolicy;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageRebalance;
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
		id, bucket, object_key, content_type, bytes, sha256, state, created_by, created_at, updated_at, deleted_at,
		version, superseded_by, expires_at, metadata_json, cache_control, content_disposition
		""";

	/**
	 * A chave resolve para a versão <b>corrente</b>. Sem o filtro de {@code superseded_by}, a
	 * segunda versão virava uma linha a mais que o cliente nunca alcançava.
	 */
	private static final String SELECT_BY_KEY = "select " + OBJECT_COLUMNS
		+ " from network_storage_objects where bucket = ? and object_key = ? and superseded_by is null"
		+ " order by version desc limit 1";

	private static final String SELECT_BY_ID = "select " + OBJECT_COLUMNS
		+ " from network_storage_objects where id = ?";

	/** Pelo digest: primeiro STORED (o caso comum), e a ordenação torna o resultado determinístico
	 * quando dois objetos distintos compartilham o mesmo conteúdo. */
	private static final String SELECT_BY_DIGEST = "select " + OBJECT_COLUMNS
		+ " from network_storage_objects where sha256 = ? and state = 'STORED' order by created_at asc";



	/**
	 * Insert-ou-noop <b>da versão corrente</b>. O {@code on duplicate key} casa com
	 * {@code uq_network_storage_objects_current}, o índice único condicional que só cobre a versão
	 * corrente de cada chave (V352). Com versionamento, a inserção de uma versão nova é feita pelo
	 * {@code insertVersion}, não por este caminho.
	 */
	private static final String INSERT_PENDING = """
		insert into network_storage_objects (id, bucket, object_key, content_type, state, created_by, created_at, updated_at)
		values (?, ?, ?, ?, 'PENDING', ?, ?, ?)
		on duplicate key update id = id
		""";

	/** A versão nova: sempre insere, nunca reaproveita. A anterior já foi marcada como substituída. */
	private static final String INSERT_VERSION = """
		insert into network_storage_objects (id, bucket, object_key, content_type, state, created_by, created_at, updated_at, version)
		values (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
		""";

	/**
	 * Substitui a versão corrente anterior. Condicional no {@code superseded_by is null}: só quem
	 * ainda é corrente é substituído, então duas gravações simultâneas não marcam a mesma versão
	 * duas vezes com dois sucessores diferente.
	 */
	private static final String MARK_SUPERSEDED = """
		update network_storage_objects
		set superseded_by = ?, updated_at = ?
		where bucket = ? and object_key = ? and superseded_by is null and state <> 'DELETED'
		""";

	private static final String SET_EXPIRES = """
		update network_storage_objects set expires_at = ? where id = ?
		""";

	/**
	 * O que o cliente declarou no começo do upload. Escrever aqui, e não no {@code markStored}, é o
	 * que faz o reenvio de um PUT interrompido (que reaproveita a linha PENDING) não herdar o
	 * metadado da gravação anterior: cada upload declara o seu.
	 */
	private static final String SET_CLIENT_HEADERS = """
		update network_storage_objects
		set metadata_json = ?, cache_control = ?, content_disposition = ?
		where id = ? and state = 'PENDING'
		""";

	private static final String SELECT_BUCKET_POLICY = """
		select bucket, versioning, retain_superseded_days, expire_after_days, updated_by, updated_at
		from network_storage_buckets where bucket = ?
		""";

	private static final String UPSERT_BUCKET_POLICY = """
		insert into network_storage_buckets (bucket, versioning, retain_superseded_days, expire_after_days,
			updated_by, updated_at)
		values (?, ?, ?, ?, ?, ?)
		on duplicate key update versioning = values(versioning),
			retain_superseded_days = values(retain_superseded_days),
			expire_after_days = values(expire_after_days),
			updated_by = values(updated_by), updated_at = values(updated_at)
		""";

	/** A ordenação é por chave ascendente e versão ascendente para o histórico ser estável. */
	private static final String SELECT_VERSIONS = """
		select %s from network_storage_objects
		where bucket = ? and object_key = ?
		order by version asc
		""".formatted(OBJECT_COLUMNS);

	/**
	 * Objetos correntes, servíveis e vencidos. O {@code expires_at is not null} é redundante com a
	 * comparação, mas deixa o índice usar a árvore: as linhas sem prazo são a maioria.
	 */
	/**
	 * Objetos com prazo vencido, corrente <b>ou substituído</b>.
	 *
	 * <p>O prazo é do objeto, e cada versão é um objeto com id próprio (doc 17 §11.1) — a versão nova
	 * não herda o prazo nem o anula. Exigir {@code superseded_by is null} aqui fazia o prazo de
	 * qualquer versão que deixou de ser corrente ser ignorado: gravado com sete dias e substituído no
	 * segundo, o conteúdo nunca vencia, nunca virava lápide, nunca era coletado, e sem política de
	 * retenção (§11.3, "sem política o padrão é guardar") ficava para sempre — na conta do dono e no
	 * disco dos nós.</p>
	 *
	 * <p>O {@code state = 'STORED'} continua: a lápide já feita não volta à varredura, e
	 * {@code PENDING} ainda não tem corpo para expirar.</p>
	 */
	private static final String SELECT_EXPIRED = """
		select %s from network_storage_objects
		where expires_at is not null and expires_at <= ? and state = 'STORED'
		order by expires_at asc
		limit ?
		""".formatted(OBJECT_COLUMNS);

	/** Versões substituídas há mais que a retenção. A lápide já foi aplicada ou ainda não: as duas contam. */
	private static final String SELECT_PURGEABLE_SUPERSEDED = """
		select %s from network_storage_objects
		where superseded_by is not null and updated_at <= ? and state <> 'DELETED'
		order by updated_at asc
		limit ?
		""".formatted(OBJECT_COLUMNS);

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
	/**
	 * Catalogo inteiro em ordem total (bucket, chave). O cursor por chave e o do contrato: a mesma
	 * chave em dois buckets aparece na mesma pagina, e como a ordem e total nenhuma linha se perde
	 * entre paginas.
	 */
	private static final String LIST_ALL = """
		select %s from network_storage_objects
		where object_key >= ? and state <> 'DELETED'
		order by bucket asc, object_key asc
		limit ?
		""".formatted(OBJECT_COLUMNS);

	/**
	 * Os buckets do dono: distintos e alfabéticos, sem as lápides (que não são conteúdo) e
	 * <b>sem o que já venceu</b>.
	 *
	 * <p>A régua é a mesma da listagem de objeto e do download: bucket cujo conteúdo inteiro venceu
	 * não aparece aqui, porque no {@code ListObjects} ele sai vazio e no {@code GET} qualquer objeto
	 * dele dá {@code 404}. Antes ele continuava listado com o critério antigo (só lápide fora), e o
	 * cliente via um nome que não abria para nada — e que o {@code DELETE} recusava com {@code 409}
	 * "ainda tem objetos". Três respostas contraditórias sobre o mesmo nome.</p>
	 */
	private static final String BUCKETS_OF = """
		select distinct bucket from network_storage_objects
		where created_by = ? and state <> 'DELETED'
		  and (expires_at is null or expires_at > ?)
		order by bucket asc
		""";

	/**
	 * A listagem de bucket, <b>sem o que já venceu</b> e <b>sem o que ainda não chegou</b>.
	 *
	 * <p><b>Ciclo 88:</b> até aqui o filtro era {@code state <> 'DELETED'}, que deixava passar as
	 * duas linhas de uma chave regravada — a versão corrente e a nova, ainda {@code PENDING} — e o
	 * {@code ListObjects} devolvia a <b>mesma chave duas vezes</b> na mesma página. Como o cursor da
	 * paginação é a chave, um cliente que seguisse o token podia nunca terminar o laço.</p>
	 *
	 * <p>A régua agora é a mesma do {@code GET}: representa a chave só o {@code STORED} — {@code
	 * PENDING} é gravação em curso (ou versão antiga que ainda vai ser colhida), e não existe para
	 * quem lista, como não existe para quem baixa. {@code superseded_by is null} garante uma linha
	 * por chave mesmo que o estado divergisse. ({@code PENDING_REMOTE} é estado de <b>colocação</b>,
	 * não de objeto: uma cópia remota ainda a caminho não muda o que o catálogo tem.)</p>
	 */
	/**
	 * A listagem de bucket, <b>sem o que já venceu</b>.
	 *
	 * <p>O download de um objeto vencido responde {@code 404} desde a fase 1 (a leitura filtra o
	 * prazo). A listagem só excluía lápide, então mostrava uma chave que o cliente não consegue
	 * baixar: para um sincronizador de diretório, "existe" e "some quando você pede" é o pior dos
	 * dois — ele pede, recebe 404 e repete, ou apaga a cópia local achando que o remoto venceu.</p>
	 *
	 * <p>O predicado é o mesmo do {@code GET} ({@code expires_at} nulo ou no futuro), escrito aqui
	 * porque a listagem é por página e filtrar depois de ler faria {@code KeyCount} e
	 * {@code IsTruncated} mentirem.</p>
	 */
	private static final String LIST = """
		select %s from network_storage_objects
		where bucket = ? and object_key like ? and object_key > ? and state = 'STORED'
		  and superseded_by is null and (expires_at is null or expires_at > ?)
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

	/**
	 * A cópia deste objeto neste nó em QUALQUER estado ativo (OK ou PENDING_REMOTE) — a fase 3
	 * precisa ver a designação que ela mesma criou, e filtrar por OK aqui devolvia "não designado"
	 * para todo objeto recém-designado. GONE fica de fora: cópia coletada não volta sem designação
	 * nova.
	 */
	private static final String SELECT_PLACEMENT_ON = "select " + PLACEMENT_COLUMNS
		+ " from network_storage_placements where object_id = ? and node_id = ? and state <> 'GONE' "
		+ "order by replica asc";

	/**
	 * Todas as cópias em qualquer estado: é a leitura da durabilidade (V320). O filtro por {@code OK}
	 * continua existindo ao lado, nomeado, porque são perguntas diferentes — e três leitores fazendo
	 * o filtro por conta própria já tinham divergido sobre "o objeto tem cópia".
	 */
	/**
	 * Objetos servíveis cuja colocação é só a réplica 0 — ou seja, nenhuma linha de nó.
	 *
	 * <p>{@code not exists} em qualquer colocação, e não {@code count(o) = 1}: objeto sem colocação
	 * nenhuma (upload anterior à colocação) também está só na réplica 0 e é candidato. O que não
	 * entra é objeto com cópia {@code STALE}/{@code GONE}/{@code PENDING_REMOTE} — esses têm o healing
	 * da cópia ruim, e pedir outra réplica por cima duplicaria a transferência.</p>
	 */
	private static final String WITH_ONLY_REPLICA_ZERO = """
		select %s from network_storage_objects o
		where o.state = 'STORED' and o.superseded_by is null and o.sha256 is not null and o.bytes is not null
		and (o.expires_at is null or o.expires_at > ?)
		and not exists (select 1 from network_storage_placements p where p.object_id = o.id and p.replica <> 0)
		order by o.created_at asc, o.id asc
		limit ?
		""".formatted(OBJECT_COLUMNS);

	private static final String SELECT_COPIES = "select " + PLACEMENT_COLUMNS
		+ " from network_storage_placements where object_id = ? order by replica asc";

	private static final String SELECT_PLACEMENTS = "select " + PLACEMENT_COLUMNS
		+ " from network_storage_placements where object_id = ? and state = 'OK' order by replica asc";

	/** Cópias num estado, das mais antigas para as mais novas: a que está parada há mais tempo vem
	 * primeiro. */
	private static final String SELECT_PLACEMENTS_BY_STATE = "select " + PLACEMENT_COLUMNS
		+ " from network_storage_placements where state = ? "
		+ "order by verified_at asc, object_id asc, replica asc";

	/**
	 * Lápides vencidas em ordem total (bucket, chave), estritamente depois do cursor. O índice
	 * {@code (state, updated_at)} da V319 filtra o estado; a ordenação vem do índice do bucket.
	 *
	 * <p>A comparação é de PAR como na ordenação ({@code bucket > ? or (bucket = ? and key > ?)}),
	 * não {@code object_key >= ?}: com o cursor só pela chave, a linha do mesmo nome no bucket
	 * seguinte fazia a página voltar — medido, o coletor paginava até o teto relendo a mesma linha.</p>
	 */
	private static final String LIST_DELETED_BEFORE = """
		select %s from network_storage_objects
		where state = 'DELETED' and deleted_at is not null and deleted_at <= ?
		  and (? = '' or bucket > ? or (bucket = ? and object_key > ?))
		order by bucket asc, object_key asc
		limit ?
		""".formatted(OBJECT_COLUMNS);



	private static final String SELECT_NODE_PLACEMENTS = "select " + PLACEMENT_COLUMNS
		+ " from network_storage_placements where node_id = ? order by verified_at desc";

	/**
	 * Resumo do catalogo inteiro em UMA consulta: quantos objetos por estado e quantos bytes eles
	 * somam. E o que o painel precisa para "capacidade total" e "espaco utilizado" sem varrer linha
	 * por linha -- e sem estimar, que e o que o brief proibe.
	 *
	 * <p>Os bytes sao os MEDIDOS na gravacao (coluna {@code bytes}), nao os declarados. Objeto
	 * PENDING tem bytes nulos e soma zero: ele ainda nao ocupa espaco medido, e contar um tamanho
	 * que nao existe inflaria o uso.</p>
	 */
	private static final String SUMMARIZE_OBJECTS = """
		select state, count(*) as objetos, coalesce(sum(bytes), 0) as bytes
		from network_storage_objects
		group by state
		""";

	/**
	 * Resumo das colocacoes por estado: quantas copias existem e quantos bytes estao em disco por
	 * estado. {@code PENDING_REMOTE} e designacao (nao ocupa disco), {@code OK} e copia medida --
	 * somar os dois daria um "espaco utilizado" que ninguem tem.
	 */
	private static final String SUMMARIZE_PLACEMENTS = """
		select state, count(*) as copias, coalesce(sum(bytes), 0) as bytes
		from network_storage_placements
		group by state
		""";

	/**
	 * Ocupacao de um participante: bytes das versoes CORRENTES que ele criou, sem lapide. E a leitura
	 * da cota (doc 20 §10). {@code coalesce} e obrigatorio: sem linha devolve NULL, e um NULL que vira
	 * zero por acidente e a diferenca entre "nao ocupa nada" e "nao medido".
	 */
	private static final String BYTES_OWNED_BY = """
		select coalesce(sum(bytes), 0)
		from network_storage_objects
		where created_by = ? and superseded_by is null and state <> 'DELETED'
		""";

	/**
	 * Por bucket (§17 do brief): quantos objetos SERVIVEIS e quantos bytes MEDIDOS eles somam.
	 *
	 * <p>Serve o "quantidade de buckets" do painel sem uma segunda consulta — o numero de linhas
	 * devolvidas e o numero de buckets. E o indice {@code (bucket, object_key)} da V319 cobre o
	 * {@code group by}: o banco le o indice, nao a tabela.</p>
	 *
	 * <p>Fica de fora o objeto {@code DELETED}: lapide nao e conteudo, e o bucket "vazio" de um
	 * objeto removido nao e um bucket que o operador tem. {@code PENDING} entra com zero bytes —
	 * ele existe no bucket e ainda nao ocupa espaco medido.</p>
	 */
	// Só a versão CORRENTE soma. A substituída continua ocupando disco (fica no histórico), mas o
	// "uso do bucket" é o que ele guarda hoje — e é o mesmo critério do `usedBytes` do painel, que
	// soma cópia OK. Somar a substituída fazia os dois números do resumo discordarem. A quantidade
	// continua contando as duas: a chave existe no bucket nas duas versões.
	private static final String SUMMARIZE_BUCKETS = """
		select bucket, count(*) as objetos,
			coalesce(sum(case when superseded_by is null then bytes end), 0) as bytes
		from network_storage_objects
		where state <> 'DELETED'
		group by bucket
		order by bucket asc
		""";

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
	public Optional<StorageObject> findByDigest(String sha256) {
		return jdbc.query(SELECT_BY_DIGEST, OBJECT_MAPPER, sha256).stream().findFirst();
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
	public StorageObject insertVersion(String id, String bucket, String key, String contentType, String createdBy,
			Instant now, long version) {
		Timestamp stamp = Timestamp.from(now);
		jdbc.update(INSERT_VERSION, id, bucket, key, contentType, createdBy, stamp, stamp, version);
		return jdbc.query(SELECT_BY_ID, OBJECT_MAPPER, id).stream().findFirst().orElseThrow(
			() -> new IllegalStateException("versao " + bucket + "/" + key + "#" + version + " invisivel apos inserir"));
	}

	@Override
	public void setClientHeaders(String id, ObjectMetadata metadata, String cacheControl, String contentDisposition) {
		// Nulo é "não declarou" e a leitura cai na política do tipo; o metadado vazio é gravado como
		// JSON vazio para o reenvio limpar o da gravação anterior em vez de deixá-lo para trás.
		jdbc.update(SET_CLIENT_HEADERS, metadata.toJson(), cacheControl, contentDisposition, id);
	}

	@Override
	public boolean markSuperseded(String bucket, String key, String supersededBy, Instant now) {
		return jdbc.update(MARK_SUPERSEDED, supersededBy, Timestamp.from(now), bucket, key) == 1;
	}

	@Override
	public void setExpiresAt(String id, Instant expiresAt) {
		jdbc.update(SET_EXPIRES, expiresAt == null ? null : Timestamp.from(expiresAt), id);
	}

	@Override
	public List<StorageObject> versions(String bucket, String key) {
		return jdbc.query(SELECT_VERSIONS, OBJECT_MAPPER, bucket, key);
	}

	@Override
	public List<StorageObject> expired(Instant now, int limit) {
		return jdbc.query(SELECT_EXPIRED, OBJECT_MAPPER, Timestamp.from(now), limit);
	}

	@Override
	public List<StorageObject> supersededBefore(Instant cutoff, int limit) {
		return jdbc.query(SELECT_PURGEABLE_SUPERSEDED, OBJECT_MAPPER, Timestamp.from(cutoff), limit);
	}

	@Override
	public Optional<StorageBucketPolicy> bucketPolicy(String bucket) {
		return jdbc.query(SELECT_BUCKET_POLICY, BUCKET_MAPPER, bucket).stream().findFirst();
	}

	@Override
	public StorageBucketPolicy saveBucketPolicy(StorageBucketPolicy policy) {
		Timestamp stamp = Timestamp.from(policy.updatedAt());
		jdbc.update(UPSERT_BUCKET_POLICY, policy.bucket(), policy.versioning(), policy.retainSupersededDays(),
			policy.expireAfterDays(), policy.updatedBy(), stamp);
		return bucketPolicy(policy.bucket()).orElseThrow(() -> new IllegalStateException(
			"politica do bucket " + policy.bucket() + " invisivel apos gravar"));
	}

	private static final RowMapper<StorageBucketPolicy> BUCKET_MAPPER = (ResultSet rs, int rowNum) ->
		new StorageBucketPolicy(rs.getString("bucket"), rs.getBoolean("versioning"),
			nullableInt(rs, "retain_superseded_days"), nullableInt(rs, "expire_after_days"),
			rs.getString("updated_by"), instant(rs, "updated_at"));

	private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	private static final String REBALANCE_COLUMNS = """
		id, object_id, from_replica, from_node_id, to_replica, to_node_id, state, failure_reason, bytes,
		bandwidth_limit_mbps, requested_by, created_at, finished_at
		""";

	private static final String INSERT_REBALANCE = """
		insert into network_storage_rebalances (id, object_id, from_replica, from_node_id, to_replica, to_node_id,
			state, bytes, bandwidth_limit_mbps, requested_by, created_at)
		values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		""";

	private static final String SELECT_REBALANCE_ACTIVE = "select " + REBALANCE_COLUMNS
		+ " from network_storage_rebalances where object_id = ? and from_replica = ? and state = 'PENDING'";

	private static final String SELECT_REBALANCE_BY_ID = "select " + REBALANCE_COLUMNS
		+ " from network_storage_rebalances where id = ?";

	private static final String SELECT_REBALANCE_ACTIVE_ALL = "select " + REBALANCE_COLUMNS
		+ " from network_storage_rebalances where state = 'PENDING' order by created_at asc limit ?";

	/** Condicional no estado de origem: duas decisões simultâneas não se sobrescrevem. */
	private static final String TRANSITION_REBALANCE = """
		update network_storage_rebalances
		set state = ?, failure_reason = ?, finished_at = ?
		where id = ? and state = ?
		""";

	private static final RowMapper<StorageRebalance> REBALANCE_MAPPER = (ResultSet rs, int rowNum) ->
		new StorageRebalance(rs.getString("id"), rs.getString("object_id"), rs.getInt("from_replica"),
			rs.getString("from_node_id"), rs.getInt("to_replica"), rs.getString("to_node_id"),
			StorageRebalance.State.valueOf(rs.getString("state")), rs.getString("failure_reason"),
			rs.getLong("bytes"), nullableInt(rs, "bandwidth_limit_mbps"), rs.getString("requested_by"),
			instant(rs, "created_at"), instant(rs, "finished_at"));

	@Override
	public void saveRebalance(StorageRebalance rebalance) {
		jdbc.update(INSERT_REBALANCE, rebalance.id(), rebalance.objectId(), rebalance.fromReplica(),
			rebalance.fromNodeId(), rebalance.toReplica(), rebalance.toNodeId(), rebalance.state().name(),
			rebalance.bytes(), rebalance.bandwidthLimitMbps(), rebalance.requestedBy(),
			Timestamp.from(rebalance.createdAt()));
	}

	@Override
	public Optional<StorageRebalance> activeRebalance(String objectId, int fromReplica) {
		return jdbc.query(SELECT_REBALANCE_ACTIVE, REBALANCE_MAPPER, objectId, fromReplica).stream().findFirst();
	}

	@Override
	public Optional<StorageRebalance> findRebalance(String id) {
		return jdbc.query(SELECT_REBALANCE_BY_ID, REBALANCE_MAPPER, id).stream().findFirst();
	}

	@Override
	public List<StorageRebalance> activeRebalances(int limit) {
		return jdbc.query(SELECT_REBALANCE_ACTIVE_ALL, REBALANCE_MAPPER, limit);
	}

	@Override
	public boolean transitionRebalance(String id, StorageRebalance.State from, StorageRebalance.State to,
			String failureReason, Instant now) {
		return jdbc.update(TRANSITION_REBALANCE, to.name(), failureReason, Timestamp.from(now), id, from.name()) == 1;
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
	public boolean discardPending(String id) {
		// Apaga a LINHA, inclusive a já STORED: a gravação que a criou acabou de ser recusada por donagem
		// (o bucket é de outra conta), e a linha do invasor não pode ficar — ela é justamente o que daria
		// o bucket a ele. O que não se apaga é o objeto do DONO: quem chama só passa o id de um objeto que
		// não existia antes do {@code PUT} recusado ({@code desfazer} em S3GatewayController), nunca o de
		// uma chave que ele reescreveu — esse caminho é o {@code restorePending}.
		jdbc.update("delete from network_storage_placements where object_id = ?", id);
		return jdbc.update("delete from network_storage_objects where id = ?", id) == 1;
	}

	@Override
	public boolean restorePending(String id) {
		return jdbc.update("""
			update network_storage_objects set state = 'PENDING', bytes = null, sha256 = null, updated_at = ?
			where id = ? and state = 'STORED'
			""", Timestamp.from(Instant.now()), id) == 1;
	}

	@Override
	public List<StorageObject> list(String bucket, String prefix, String afterKey, int limit) {
		// O LIKE é prefixo literal: o '%' do cliente seria curinga e listaria outra coisa.
		String pattern = escapeLike(prefix == null ? "" : prefix) + "%";
		String after = afterKey == null ? "" : afterKey;
		return jdbc.query(LIST, OBJECT_MAPPER, bucket, pattern, after, Timestamp.from(Instant.now()), limit);
	}

	@Override
	public List<String> bucketsOf(String ownerUserId) {
		return jdbc.query(BUCKETS_OF, (rs, row) -> rs.getString(1), ownerUserId, Timestamp.from(Instant.now()));
	}

	@Override
	public List<StorageObject> listAll(String afterKey, int limit) {
		return jdbc.query(LIST_ALL, OBJECT_MAPPER, afterKey == null ? "" : afterKey, limit);
	}

	@Override
	public void savePlacement(StoragePlacement placement) {
		jdbc.update(UPSERT_PLACEMENT, placement.objectId(), placement.replica(), placement.nodeId(),
			placement.bytes(), placement.sha256(), placement.state().name(),
			Timestamp.from(placement.verifiedAt()));
	}

	@Override
	public List<StoragePlacement> copies(String objectId) {
		return jdbc.query(SELECT_COPIES, PLACEMENT_MAPPER, objectId);
	}

	@Override
	public List<StoragePlacement> placements(String objectId) {
		return jdbc.query(SELECT_PLACEMENTS, PLACEMENT_MAPPER, objectId);
	}

	@Override
	public List<StorageObject> withOnlyReplicaZero(int limit) {
		// O "agora" vem do Java, não do relógio do banco: é o mesmo instante em que a varredura e a
		// leitura decidem que um objeto venceu, e um fuso do servidor não muda a resposta.
		return jdbc.query(WITH_ONLY_REPLICA_ZERO, OBJECT_MAPPER, Timestamp.from(Instant.now()), limit);
	}

	@Override
	public List<StoragePlacement> placementsInState(StoragePlacement.State state) {
		return jdbc.query(SELECT_PLACEMENTS_BY_STATE, PLACEMENT_MAPPER, state.name());
	}

	@Override
	public List<StorageObject> listDeletedBefore(Instant before, String afterBucket, String afterKey, int limit) {
		// Cursor nulo vira par vazio: `? = ''` desliga o filtro e a primeira página começa do início
		// de todos os buckets, inclusive de bucket que começa com o menor byte.
		String bucket = afterBucket == null ? "" : afterBucket;
		String key = afterKey == null ? "" : afterKey;
		String semCursor = afterBucket == null ? "" : "x";
		return jdbc.query(LIST_DELETED_BEFORE, OBJECT_MAPPER, Timestamp.from(before), semCursor, bucket, bucket,
			key, limit);
	}

	@Override
	public Optional<StoragePlacement> placementOn(String objectId, String nodeId) {
		return jdbc.query(SELECT_PLACEMENT_ON, PLACEMENT_MAPPER, objectId, nodeId).stream().findFirst();
	}

	@Override
	public long bytesOwnedBy(String ownerUserId) {
		Long total = jdbc.queryForObject(BYTES_OWNED_BY, Long.class, ownerUserId);
		return total == null ? 0L : total;
	}

	@Override
	public java.util.Map<String, long[]> summarizeObjects() {
		java.util.Map<String, long[]> porEstado = new java.util.LinkedHashMap<>();
		jdbc.query(SUMMARIZE_OBJECTS, (rs) -> {
			porEstado.put(rs.getString("state"), new long[] { rs.getLong("objetos"), rs.getLong("bytes") });
		});
		return porEstado;
	}

	@Override
	public java.util.Map<String, long[]> summarizePlacements() {
		java.util.Map<String, long[]> porEstado = new java.util.LinkedHashMap<>();
		jdbc.query(SUMMARIZE_PLACEMENTS, (rs) -> {
			porEstado.put(rs.getString("state"), new long[] { rs.getLong("copias"), rs.getLong("bytes") });
		});
		return porEstado;
	}

	@Override
	public java.util.Map<String, long[]> summarizeBuckets() {
		java.util.Map<String, long[]> porBucket = new java.util.LinkedHashMap<>();
		jdbc.query(SUMMARIZE_BUCKETS, (rs) -> {
			porBucket.put(rs.getString("bucket"), new long[] { rs.getLong("objetos"), rs.getLong("bytes") });
		});
		return porBucket;
	}

	@Override
	public List<StoragePlacement> placementsOnNode(String nodeId) {
		return jdbc.query(SELECT_NODE_PLACEMENTS, PLACEMENT_MAPPER, nodeId);
	}

	@Override
	public List<StoragePlacement> copiesOnNode(String nodeId) {
		// Em qualquer estado: a colocação precisa ver também a designação pendente, que reserva folga.
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
		instant(rs, "deleted_at"), rs.getLong("version"), rs.getString("superseded_by"),
		instant(rs, "expires_at"), ObjectMetadata.fromJson(rs.getString("metadata_json")),
		rs.getString("cache_control"), rs.getString("content_disposition"));

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