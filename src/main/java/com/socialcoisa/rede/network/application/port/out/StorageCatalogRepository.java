package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.network.domain.ObjectMetadata;
import com.socialcoisa.rede.network.domain.StorageBucketPolicy;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageRebalance;
import com.socialcoisa.rede.network.domain.StoragePlacement;

/**
 * O catálogo do Énóis Storage (V319/V320): o objeto lógico e a colocação das cópias nos nós.
 *
 * <p>Esta porta é a fronteira que o brief exige: o resto do backend pergunta ao catálogo "onde
 * está este objeto?" e nunca fala com um storage node. A localização física muda (replicação,
 * healing, nó que sai) sem que nada acima saiba.</p>
 */
public interface StorageCatalogRepository {

	/**
	 * O objeto por bucket e chave: a versão <b>corrente</b>, inclusive lapidada — a lápide também
	 * precisa responder "já existiu".
	 */
	Optional<StorageObject> find(String bucket, String key);

	/** O objeto por id, para as operações que já o resolveram (upload, download, remoção). */
	Optional<StorageObject> findById(String id);

	/**
	 * O objeto pelo sha-256 do conteúdo (fase 3), para a réplica que conhece a cópia pelo digest e
	 * não pelo id lógico. Só objetos com conteúdo medido têm digest único: dois {@code PENDING} não
	 * têm {@code sha256}, e dois objetos distintos com o mesmo conteúdo compartilham o digest — o
	 * que é próprio do identificador de conteúdo.
	 */
	Optional<StorageObject> findByDigest(String sha256);

	/**
	 * Cria o objeto em {@code PENDING}, ou devolve o existente. A segunda chamada do mesmo cliente
	 * não duplica: {@code bucket + key} é única no banco e a consulta é o que decide.
	 */
	StorageObject createPending(String id, String bucket, String key, String contentType, String createdBy,
			Instant now);

	/** O objeto ganhou cópia inteira: bytes e hash medidos, estado {@code STORED}. */
	boolean markStored(String id, long bytes, String sha256, Instant now);

	/** Lápide. Devolve {@code false} se já estava removido — repetir a remoção não é erro. */
	boolean markDeleted(String id, Instant now);

	/**
	 * Apaga a linha de um objeto <b>que nunca chegou a existir para o dono</b>: uma gravação desfeita
	 * porque o bucket era de outra conta.
	 *
	 * <p>Não é a lápide, e a diferença é o que mantém o bucket do dono de pé: a lápide
	 * ({@code state = 'DELETED'}) **sai** do {@code bucketsOf}, então apagar o último objeto de uma conta
	 * com lápide faria o bucket deixar de ser dela. Esta remoção some da tabela sem deixar estado — é a
	 * inversão de um {@code createPending}, não uma operação do dono.</p>
	 */
	boolean discardPending(String id);

	/**
	 * Devolve um objeto <b>reescrito</b> ao retrato que ele tinha antes da gravação recusada por donagem.
	 *
	 * <p>É o par de {@link #markStored} na direção contrária, e existe para a recusa por donagem de um
	 * {@code PUT} que <b>reescreveu</b> uma chave: apagar a linha seria apagar o objeto do dono.</p>
	 *
	 * <p><b>Antes:</b> a volta era só {@code STORED → PENDING} com {@code bytes} e {@code sha256}
	 * apagados, e o tipo, o metadado e o prazo ficavam os da gravação recusada. O objeto do dono saía da
	 * listagem e respondia {@code 404} no {@code GET} e no {@code HEAD}, com o nome de anexo do invasor
	 * por cima.</p>
	 *
	 * <p><b>Agora:</b> recebe o retrato lido antes de abrir a gravação e o devolve inteiro. Um objeto que
	 * tinha corpo volta a {@code STORED} com o corpo e o digest de antes — é o que o torna servível de
	 * novo; o que só existia como espera volta a {@code PENDING}, com o tipo, o metadado e o prazo que
	 * a linha trazia (a abertura de uma versão nova já nasce com eles, então a devolução não os zera).</p>
	 */
	boolean restorePending(String id, String contentType, Long bytes, String sha256, ObjectMetadata metadata,
			String cacheControl, String contentDisposition, Instant expiresAt);

	/**
	 * Listagem por prefixo de chave, ordenada por chave. {@code limit + 1} linhas é o que o chamador
	 * pede para saber se há próxima página sem uma segunda consulta de contagem.
	 */
	List<StorageObject> list(String bucket, String prefix, String afterKey, int limit);

	/**
	 * Os buckets com pelo menos um objeto do dono, em ordem alfabética — o ListBuckets do gateway S3.
	 *
	 * <p>Por <b>dono</b> e não por instalação: um cliente S3 não pode listar o bucket de outra conta.
	 * Buckets do Storage não têm tabela própria (o nome nasce no primeiro upload), então a lista sai
	 * dos objetos que existem.</p>
	 */
	List<String> bucketsOf(String ownerUserId);

	/**
	 * O dono registrado do bucket, ou {@code null} se ninguém o registrou ainda.
	 *
	 * <p>O {@code CreateBucket} grava o criador em {@code network_storage_buckets.updated_by} — é o
	 * que permite responder {@code 409 BucketAlreadyExists} antes de o bucket ter o primeiro objeto.
	 * Para o bucket que nasceu do primeiro upload (sem linha de política), a resposta é o dono do
	 * objeto mais antigo: a mesma régua do {@code bucketsOf}.</p>
	 */
	default java.util.Optional<String> ownerDoBucket(String bucket) {
		// O criador registrado na linha do bucket manda, e nunca é reescrito (`V373`). Sem ele —
		// bucket anterior à coluna — vale o dono do primeiro objeto vivo: a régua do `bucketsOf`,
		// que o CreateBucket usa quando o bucket nasce do primeiro upload.
		java.util.Optional<String> criador = bucketPolicy(bucket).map(StorageBucketPolicy::ownerUserId)
			.filter(id -> id != null && !id.isBlank());
		if (criador.isPresent()) {
			return criador;
		}
		return list(bucket, null, null, 1000).stream()
			.filter(o -> !"DELETED".equals(o.state()))
			.map(StorageObject::createdBy)
			.filter(java.util.Objects::nonNull)
			.findFirst();
	}


	/**
	 * O bucket tem objeto no catálogo (vivo ou lápide) e <b>algum</b> é deste dono?
	 *
	 * <p>É a donagem do {@code DeleteBucket}: {@link #bucketsOf} conta só o vivo, e depois da última
	 * lápide o dono deixaria de ver o próprio bucket. Vivo, a resposta é a mesma; esvaziado, é a
	 * única prova que sobra.</p>
	 */
	boolean bucketHasObjectsOf(String bucket, String ownerUserId);

	/**
	 * Todos os objetos servíveis, ordenados por {@code (bucket, object_key)} — a paginação da
	 * varredura percorre o catálogo inteiro, não um bucket.
	 *
	 * <p><b>Ciclo 102:</b> o cursor é o <b>par</b>, não a chave. Ordenar por
	 * {@code (bucket, object_key)} e paginar por chave só fazia a página seguinte procurar
	 * {@code key >= última} em <i>qualquer</i> bucket: depois de {@code fotos/z.jpg}, um
	 * {@code midia/a.mp4} (chave menor, bucket posterior) era pulado para sempre. O javadoc antigo
	 * dizia que "não perde nem duplica" — a ordenação era total, mas o filtro não era.</p>
	 *
	 * <p>{@code afterBucket} nulo começa do começo. O par do último item da página é o cursor da
	 * próxima, e o cursor é <b>exclusivo</b>: o item do cursor não volta.</p>
	 */
	List<StorageObject> listAll(String afterBucket, String afterKey, int limit);

	/** Grava (ou substitui) a cópia {@code replica} deste objeto neste nó. */
	void savePlacement(StoragePlacement placement);

	/**
	 * Todas as cópias deste objeto em <b>qualquer</b> estado, em ordem de réplica.
	 *
	 * <p>É a leitura que a durabilidade usa, e o nome diz isso: uma cópia {@code GONE} ainda conta
	 * como réplica existente. Quem pergunta "quantas cópias o objeto tem" precisa deste número —
	 * {@code placements} descreve o que é <i>servível</i>, que é outra pergunta.</p>
	 */
	List<StoragePlacement> copies(String objectId);

	/**
	 * Todas as colocações de um nó, em qualquer estado. É o que a colocação lê para saber o que o nó
	 * já reservou (designação pendente) além do que confirmou.
	 */
	List<StoragePlacement> copiesOnNode(String nodeId);

	/**
	 * As cópias deste objeto ainda servíveis (estado {@code OK}), em ordem de réplica.
	 *
	 * <p>Uso de leitura/serving: objeto que voltou a ter cópia confirmada. Para durabilidade use
	 * {@link #copies(String)}.</p>
	 */
	List<StoragePlacement> placements(String objectId);

	/**
	 * Objetos servíveis que têm <b>só a réplica 0</b> na colocação: nada de nó, nem cópia nem
	 * designação. É a candidata do healing de "réplica única" (doc 13 §5.10): a réplica 0 é o disco
	 * do Control Plane, e durabilidade é cópia de nó.
	 */
	List<StorageObject> withOnlyReplicaZero(int limit);

	/**
	 * Lápides vencidas, em ordem total {@code (bucket, object_key)}, estritamente <b>depois</b> do
	 * cursor. O cursor é o par {@code (bucket, chave)} e não só a chave: a mesma chave em dois
	 * buckets é legítima, e um cursor só pela chave oscilaria entre as duas linhas para sempre.
	 *
	 * <p>Cada linha devolvida está além do cursor, então uma página que não rendeu (todos os objetos
	 * pulados) ainda avança — nenhuma chamada repete o que já foi decidido.</p>
	 */
	List<StorageObject> listDeletedBefore(Instant before, String afterBucket, String afterKey, int limit);

	/** A cópia servível deste objeto num nó específico, se existir. */
	/**
	 * A cópia deste objeto neste nó em qualquer estado vivo ({@code OK} ou {@code PENDING_REMOTE}).
	 * A fase 3 usa esta leitura para a própria designação se confirmar; filtrar por {@code OK} aqui
	 * esconderia a linha que a designação acabou de criar.
	 */
	Optional<StoragePlacement> placementOn(String objectId, String nodeId);

	/**
	 * Objetos do catálogo inteiro por estado: {@code {quantidade, bytes}} cada. Uma consulta só, para
	 * o painel — varrer o catálogo para somar seria contar linha a linha o que o banco já sabe.
	 *
	 * <p>Estado ausente no mapa é "nenhum objeto naquele estado", que é diferente de zero objetos
	 * encontrados. Quem consome deve tratar a ausência como zero explícito na soma, nunca como
	 * "não medido".</p>
	 */
	java.util.Map<String, long[]> summarizeObjects();

	/**
	 * Os bytes que o participante ocupa no catálogo: soma de {@code bytes} das versões <b>correntes</b>
	 * que ele criou, sem lápide. É a base da cota por participante (doc 20 §10) — versão substituída já
	 * não é conteúdo que o download entrega, e lápide não é conteúdo nenhum.
	 */
	long bytesOwnedBy(String ownerUserId);

	/** Cópias por estado: {@code {quantidade, bytes}} cada. Mesma regra de ausência acima. */
	java.util.Map<String, long[]> summarizePlacements();

	/**
	 * Objetos servíveis por bucket: {@code {quantidade, bytes}} cada, em ordem de bucket.
	 *
	 * <p>O tamanho do mapa é a resposta do "quantidade de buckets" do painel (§17) — uma consulta
	 * agregada em vez de uma por bucket. Objeto {@code DELETED} não entra: lápide não é conteúdo,
	 * e contar o bucket dela mostraria um bucket que o operador não tem.</p>
	 */
	java.util.Map<String, long[]> summarizeBuckets();

	/** O que este nó hospeda, para o painel e para o nó saber o que tem. */
	List<StoragePlacement> placementsOnNode(String nodeId);

	/**
	 * As cópias de <b>todos</b> os objetos num estado, em ordem estável.
	 *
	 * <p>É a leitura que a cura precisa para achar as {@code STALE} que a varredura do próprio
	 * disco não vê: a cópia de outro nó nunca passa por este disco, e o sentido dela só existe no
	 * catálogo. A ordem é {@code (verified_at, object_id, replica)} — a mais antiga primeiro, que
	 * é a que está parada há mais tempo.</p>
	 */
	List<StoragePlacement> placementsInState(StoragePlacement.State state);

	/**
	 * Cria a <b>versão nova</b> de uma chave. Diferente de {@link #createPending}: nunca reaproveita
	 * linha. A versão corrente anterior tem de já ter sido marcada por {@link #markSuperseded} — o
	 * índice único condicional recusa duas correntes da mesma chave.
	 */
	StorageObject insertVersion(String id, String bucket, String key, String contentType, String createdBy,
			Instant now, long version);

	/**
	 * Marca a versão corrente de {@code bucket/key} como substituída por {@code supersededBy}.
	 * Devolve {@code false} quando não havia corrente (concorrência: outra gravação chegou antes).
	 */
	boolean markSuperseded(String bucket, String key, String supersededBy, Instant now);

	/** Prazo de vida do objeto; nulo remove o prazo. */
	void setExpiresAt(String id, Instant expiresAt);

	/** Grava o metadado e os cabeçalhos que o cliente declarou no upload (V367). */
	void setClientHeaders(String id, com.socialcoisa.rede.network.domain.ObjectMetadata metadata,
			String cacheControl, String contentDisposition);

	/** Todas as versões da chave em ordem crescente — o histórico, incluindo a corrente. */
	List<StorageObject> versions(String bucket, String key);

	/**
	 * Todas as versões de todas as chaves do bucket — o {@code ListObjectVersions} do gateway S3.
	 *
	 * <p>Ordem por chave e por versão ascendente, como o histórico de uma chave: a listagem por
	 * bucket mantém a mesma régua para que paginar por chave continue estável. {@code prefix}
	 * {@code null} ou vazio é "todas".</p>
	 */
	default List<StorageObject> versionsNoBucket(String bucket, String prefix) {
		throw new UnsupportedOperationException(
			"versionsNoBucket: o catalogo em memoria nao implementa o historico por bucket");
	}

	/** Versões correntes, servíveis e vencidas até {@code now}, em ordem de vencimento. */
	List<StorageObject> expired(Instant now, int limit);

	/** Versões substituídas antes de {@code cutoff} e ainda sem lápide — o que a retenção libera. */
	List<StorageObject> supersededBefore(Instant cutoff, int limit);

	/** Política do bucket; vazio quando ninguém a configurou (os padrões valem). */
	Optional<StorageBucketPolicy> bucketPolicy(String bucket);

	/** Grava (ou substitui) a política do bucket e devolve o que ficou gravado. */
	StorageBucketPolicy saveBucketPolicy(StorageBucketPolicy policy);

	/** Grava a transferência de rebalanceamento (brief §16). */
	void saveRebalance(StorageRebalance rebalance);

	/** A transferência viva (PENDING) de uma réplica de origem, se houver. */
	Optional<StorageRebalance> activeRebalance(String objectId, int fromReplica);

	/** Uma transferência pelo id. */
	Optional<StorageRebalance> findRebalance(String id);

	/** Transferências vivas, em ordem de criação — o que o operador pausa ou retoma. */
	List<StorageRebalance> activeRebalances(int limit);

	/** Mudança de estado de uma transferência, condicional ao estado de origem. */
	boolean transitionRebalance(String id, StorageRebalance.State from, StorageRebalance.State to,
			String failureReason, Instant now);
}