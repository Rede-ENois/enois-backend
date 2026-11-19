package com.socialcoisa.rede.network.application;

import java.io.IOException;
import java.io.InputStream;

import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.ObjectMetadata;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.network.domain.StorageBucketPolicy;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O catálogo do Énóis Storage em funcionamento (fase 1).
 *
 * <p><b>O Control Plane não transporta o conteúdo.</b> O brief (§3.1) é explícito, e o modelo que
 * atende é o mesmo que a rede já usa para tarefas: o cliente faz o upload <b>para o nó</b>, e o
 * Control Plane guarda só o catálogo (identidade, tamanho, hash, colocação). É por isso que
 * {@link #receiveUpload} grava no disco local do próprio Control Plane nesta fase — ele está
 * fazendo o papel do primeiro nó, e a rota que o nó usa em produção é a mesma (PUT do corpo,
 * medido, colocação gravada). A fase 2 troca quem recebe o corpo, não o contrato.</p>
 *
 * <p><b>"Gravou" só vale quando foi medido.</b> O tamanho e o SHA-256 que ficam no catálogo são os
 * que este serviço calcula enquanto grava — nunca os que o cliente ou o nó declararam. É o mesmo
 * critério do {@code MediaResultVerifier} (RXX): upload que retorna 200 sem conferência é
 * confiança, não durabilidade.</p>
 *
 * <p><b>Limites são do participante.</b> O teto de tamanho vem da configuração do nó/CP
 * ({@code rede.storage.max-object-bytes}) e é recusado <b>antes</b> de gravar: um corpo maior que
 * o teto é descartado por leitura, não escrito e apagado depois — no disco de quem contribui, os
 * dois caminhos têm custo diferente.</p>
 */
@Service
public class StorageCatalogService {

	/** Prefixo das chaves de objeto; mesmo padrão dos demais ids do módulo. */
	private static final String OBJECT_ID_PREFIX = "sob_";

	/** Teto do tamanho da listagem por página: o cliente S3 pede até 1000. */
	public static final int MAX_LIST_LIMIT = 1000;

	/**
	 * Piso de disco livre medido para o nó receber um corpo.
	 *
	 * <p>Não é o espaço ocupado pelo objeto — é a folga que o sistema de arquivos precisa para o
	 * trabalho que o próprio nó faz em volta dele (temporário do upload, journal, arquivos de outro
	 * processo). Um disco a menos de 1 GiB livre falha no meio de qualquer gravação sensata, e falhar
	 * depois de receber o arquivo custa ao participante o que recusar antes não custa.</p>
	 */
	public static final long MIN_DISK_FREE_MB = 1024;

	private final StorageCatalogRepository catalog;
	private final AuditTrail audit;
	private final Clock clock;
	private final long maxObjectBytes;
	/**
	 * Onde este Control Plane guarda o corpo que ele mesmo recebeu. Na fase 1 o CP faz o papel do
	 * primeiro nó; na fase 2 o corpo vai para o nó designado e este diretório deixa de ser usado.
	 */
	private final String storageRoot;
	/**
	 * Quem escolhe os nós que recebem cópia (fase 3). Fica aqui porque a designação é decidida no
	 * mesmo passo em que o objeto passa a ter conteúdo: a colocação nasce com a gravação, não num
	 * segundo momento em que o objeto já seria servível sem saber quem o tem.
	 */
	private final StoragePlacementService placement;
	/**
	 * O corpo que o nó que roda <b>nesta mesma máquina</b> guarda no próprio disco
	 * ({@code rede.storage.node-body-root}). Nulo = esta instalação não tem nó local de corpos; é o
	 * caso do Control Plane puro e da suíte.
	 */
	private final NodeBodyStore nodeBodies;
	/**
	 * Envelope do corpo (brief §10): chave de dados por objeto, embrulhada pela chave mestra.
	 * Nulo = instalação sem chave; nesse caso o corpo é guardado em claro, e o doc 19 declara.
	 */
	private final EnvelopeBodyCrypto envelope;

	/**
	 * Contador de operação do painel (§17, V366). <b>Nulo é válido</b>: os testes de outros assuntos do
	 * catálogo constroem o serviço sem o contador, e a métrica é observação — não pode ser pré-requisito
	 * para gravar um objeto.
	 */
	@Nullable
	private final StorageOperationMetrics metrics;

	/**
	 * A forma anterior, sem chave: instalação que ainda não configurou a mestra grava em claro. Os
	 * testes de outros assuntos do catálogo não passam a depender do envelope por causa disto.
	 */
	StorageCatalogService(StorageCatalogRepository catalog, AuditTrail audit, Clock clock,
			long maxObjectBytes, String storageRoot, StoragePlacementService placement,
			@Nullable NodeBodyStore nodeBodies) {
		this(catalog, audit, clock, maxObjectBytes, storageRoot, "", placement, nodeBodies, null);
	}

	StorageCatalogService(StorageCatalogRepository catalog, AuditTrail audit, Clock clock,
			@Value("${rede.storage.max-object-bytes:5368709120}") long maxObjectBytes,
			@Value("${rede.storage.root:${java.io.tmpdir}/enois-storage}") String storageRoot,
			@Value("${rede.security.encryption-key:}") String encryptionKey,
			StoragePlacementService placement, @Nullable NodeBodyStore nodeBodies) {
		this(catalog, audit, clock, maxObjectBytes, storageRoot, encryptionKey, placement, nodeBodies, null);
	}

	/**
	 * Com o contador de operação: é esta a forma que o Spring injeta (o {@code @Autowired} fica neste
	 * construtor). A delegação acima mantém os testes de outros assuntos com a assinatura que já usavam.
	 */
	@org.springframework.beans.factory.annotation.Autowired
	StorageCatalogService(StorageCatalogRepository catalog, AuditTrail audit, Clock clock,
			@Value("${rede.storage.max-object-bytes:5368709120}") long maxObjectBytes,
			@Value("${rede.storage.root:${java.io.tmpdir}/enois-storage}") String storageRoot,
			@Value("${rede.security.encryption-key:}") String encryptionKey,
			StoragePlacementService placement, @Nullable NodeBodyStore nodeBodies,
			@Nullable StorageOperationMetrics metrics) {
		this.catalog = catalog;
		this.audit = audit;
		this.clock = clock;
		this.maxObjectBytes = maxObjectBytes;
		this.storageRoot = storageRoot;
		this.placement = placement;
		this.nodeBodies = nodeBodies;
		this.envelope = encryptionKey == null || encryptionKey.isBlank() ? null
			: new EnvelopeBodyCrypto(java.util.Base64.getDecoder().decode(encryptionKey.trim()));
		this.metrics = metrics;
	}

	/**
	 * Cria (ou devolve) o objeto lógico antes de o corpo chegar. Idempotente por
	 * {@code bucket + chave}: o retry do cliente é o caso comum, não a exceção.
	 */
	@Transactional
	public StorageObject beginUpload(String actorId, String bucket, String key, String contentType) {
		validateBucket(bucket);
		validateKey(key);
		String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
		Instant now = clock.instant();
		StorageBucketPolicy policy = bucketPolicy(bucket);

		if (policy.versioning()) {
			// Bucket com versionamento: cada upload é uma versão nova. A corrente anterior sai da
			// frente ANTES, porque o índice único condicional só aceita uma corrente por chave; se
			// outra gravação venceu a corrida, esta tenta de novo com o próximo número em vez de
			// derrubar a chave inteira.
			return beginVersionedUpload(actorId, bucket, key, type, now, policy);
		}

		StorageObject object = catalog.createPending(OBJECT_ID_PREFIX + NodeIds.next(""), bucket, key, type,
			actorId, now);
		if (object.state() == StorageObjectState.DELETED) {
			// A chave é um objeto novo depois da lápide: reabrir tem de ser explícito, senão o cliente
			// reenvia o arquivo e nada aparece no download — falha silenciosa do pior tipo.
			throw new ApiException(409, "STORAGE_KEY_DELETED",
				"Esta chave foi removida e ainda espera coleta no no; remova a lapide ou use outra chave.");
		}
		Instant expiresAt = policy.expiresAtFor(now);
		if (expiresAt != null && object.expiresAt() == null && object.version() == 1L) {
			// O prazo é do bucket no momento da gravação; objeto que já existe mantém o que tinha.
			catalog.setExpiresAt(object.id(), expiresAt);
			object = catalog.findById(object.id()).orElse(object);
		}
		return object;
	}

	/**
	 * O que o cliente declarou junto do corpo (V367): {@code x-amz-meta-*}, {@code Cache-Control} e
	 * {@code Content-Disposition}. Vai num objeto só porque é declarado no mesmo instante e gravado na
	 * mesma linha.
	 */
	public record ClientHeaders(ObjectMetadata metadata, String cacheControl, String contentDisposition) {

		public static final ClientHeaders NONE = new ClientHeaders(ObjectMetadata.EMPTY, null, null);

		public ClientHeaders {
			metadata = metadata == null ? ObjectMetadata.EMPTY : metadata;
			cacheControl = limpo(cacheControl);
			contentDisposition = limpo(contentDisposition);
		}

		private static String limpo(String valor) {
			return valor == null || valor.isBlank() ? null : valor.trim();
		}
	}

	/** Teto de {@code Cache-Control} e {@code Content-Disposition}: cabeçalho, não corpo. */
	public static final int MAX_CLIENT_HEADER = 200;

	/**
	 * Valida o {@code Cache-Control} e o {@code Content-Disposition} declarados no upload.
	 *
	 * <p>Os valores voltam como cabeçalho HTTP no GET e no HEAD, e um {@code 
} dentro deles seria
	 * injeção de resposta. Recusar o caractere de controle é a regra; estourar o tamanho também, em vez
	 * de truncar — um {@code Content-Disposition} cortado entrega o arquivo com outro nome.</p>
	 */
	public static ClientHeaders clientHeaders(String cacheControl, String contentDisposition,
			ObjectMetadata metadata) {
		for (String valor : new String[] { cacheControl, contentDisposition }) {
			if (valor == null) {
				continue;
			}
			if (valor.length() > MAX_CLIENT_HEADER) {
				throw new ApiException(400, "InvalidArgument",
					"Cache-Control e Content-Disposition do objeto cabem em " + MAX_CLIENT_HEADER + " caracteres.");
			}
			for (int i = 0; i < valor.length(); i++) {
				if (valor.charAt(i) < 0x20 && valor.charAt(i) != '\t' || valor.charAt(i) == 0x7f) {
					throw new ApiException(400, "InvalidArgument",
						"Cache-Control e Content-Disposition nao aceitam caractere de controle.");
				}
			}
		}
		return new ClientHeaders(metadata, cacheControl, contentDisposition);
	}

	/**
	 * Grava o que o cliente declarou no upload que acabou de começar.
	 *
	 * <p>Separado do {@code beginUpload} porque o multipart só sabe o metadado na criação do upload e
	 * os cabeçalhos já vêm do {@code CreateMultipartUpload}: o serviço de multipart conta o que tem, e
	 * o caminho é o mesmo do PUT simples — um método, não duas gravações que podem divergir.</p>
	 */
	public void saveClientHeaders(StorageObject object, ClientHeaders headers) {
		if (object == null || headers == null) {
			return;
		}
		if (headers.metadata().isEmpty() && headers.cacheControl() == null
				&& headers.contentDisposition() == null) {
			// Nada declarado é o caso comum e o valor da coluna já é nulo: não vale um UPDATE por PUT.
			return;
		}
		catalog.setClientHeaders(object.id(), headers.metadata(), headers.cacheControl(),
			headers.contentDisposition());
	}

	/** Quantas vezes a gravação versionada tenta de novo quando outra requisição venceu a corrida. */
	private static final int VERSIONED_ATTEMPTS = 3;

	private StorageObject beginVersionedUpload(String actorId, String bucket, String key, String type, Instant now,
			StorageBucketPolicy policy) {
		// Idempotência do versionamento: o MESMO cliente repetindo o upload do mesmo conteúdo ainda
		// não confirmado tem de receber a mesma versão. Sem isso, retry de rede criaria v2, v3, v4 de
		// um arquivo que nunca mudou, e a retenção guardaria tudo.
		for (int attempt = 0; attempt < VERSIONED_ATTEMPTS; attempt++) {
			Optional<StorageObject> current = catalog.find(bucket, key);
			if (current.isPresent() && current.get().state() == StorageObjectState.PENDING) {
				return current.get();
			}
			long version = current.map(o -> o.version() + 1).orElse(1L);
			String id = OBJECT_ID_PREFIX + NodeIds.next("");
			if (current.isPresent() && current.get().state() == StorageObjectState.DELETED
					&& !policy.versioning()) {
				throw new ApiException(409, "STORAGE_KEY_DELETED",
					"Esta chave foi removida e ainda espera coleta no no; remova a lapide ou use outra chave.");
			}
			if (current.isPresent() && !catalog.markSuperseded(bucket, key, id, now)) {
				// Outra gravação chegou primeiro: esta versão não é a próxima, e o número precisa ser
				// relido. Recomeçar é mais seguro do que adivinhar.
				continue;
			}
			StorageObject created = catalog.insertVersion(id, bucket, key, type, actorId, now, version);
			Instant expiresAt = policy.expiresAtFor(now);
			if (expiresAt != null) {
				catalog.setExpiresAt(created.id(), expiresAt);
				created = catalog.findById(created.id()).orElse(created);
			}
			audit.record(actorId, "STORAGE_VERSION_CREATED", created.id(),
				"{\"bucket\":\"" + escape(bucket) + "\",\"key\":\"" + escape(key) + "\",\"version\":"
					+ version + "}");
			return created;
		}
		throw new ApiException(409, "STORAGE_VERSION_RACE",
			"Outra gravacao desta chave chegou ao mesmo tempo; tente novamente.");
	}

	/**
	 * O upload do cliente S3 (brief §11): o corpo chega pelo gateway, não por um nó.
	 *
	 * <p><b>É o mesmo caminho do {@link #store}</b>, com uma diferença de origem: aqui quem grava a
	 * réplica 0 é o Control Plane, e o nó recebe a designação como sempre. Duplicar o registro do
	 * catálogo seria criar um segundo Storage — o que o contrato proíbe.</p>
	 *
	 * <p>Diferente do {@link #store}, que recebe um nó já autorizado, este ponto expõe o teto de
	 * tamanho e o corpo vazio ao cliente — porque é o cliente que erra, e o erro tem de ser 413/400,
	 * não 500.</p>
	 */
	public StorageObject finishUpload(String objectId, InputStream body, String actorId) {
		long inicio = System.nanoTime();
		try {
			StorageObject gravado = doFinishUpload(objectId, body, actorId);
			conta("PUT", StorageOperationMetrics.Resultado.OK, inicio);
			return gravado;
		}
		catch (RuntimeException recusado) {
			// Qualquer recusa do caminho de gravação é pedido do cliente: objeto vencido, corpo vazio,
			// acima do teto, sem raiz de disco. A falha É o que o painel precisa ver separada de OK.
			conta("PUT", resultadoDe(recusado), inicio);
			throw recusado;
		}
	}

	private StorageObject doFinishUpload(String objectId, InputStream body, String actorId) {
		StorageObject object = catalog.findById(objectId)
			.orElseThrow(() -> new ApiException(404, "STORAGE_OBJECT_UNKNOWN", "Objeto nao encontrado no catalogo."));
		if (object.state() != StorageObjectState.PENDING) {
			throw new ApiException(409, "STORAGE_ALREADY_STORED", "O objeto ja tem copia confirmada.");
		}
		Spooled measured = measure(body);
		if (measured.bytes() == 0) {
			deleteQuietly(measured.temp());
			throw new ApiException(400, "STORAGE_EMPTY_BODY", "O corpo enviado esta vazio.");
		}
		if (measured.bytes() > maxObjectBytes) {
			deleteQuietly(measured.temp());
			throw new ApiException(413, "STORAGE_OBJECT_TOO_LARGE",
				"O objeto excede o teto desta instalacao (" + maxObjectBytes + " bytes).");
		}
		Path destination = bodyPath(object.id());
		try {
			if (envelope == null) {
				Files.move(measured.temp(), destination, StandardCopyOption.REPLACE_EXISTING);
			}
			else {
				// O digest medido é do TEXTO CLARO, e continua sendo: o catálogo promete o sha-256 do
				// que o cliente mandou, não do que está no disco. O arquivo é cifrado com a DEK deste
				// objeto; o texto claro só existe no temporário, que sai logo abaixo.
				envelope.encryptTo(measured.temp(), destination, root());
				deleteQuietly(measured.temp());
			}
		}
		catch (IOException unwritable) {
			deleteQuietly(measured.temp());
			throw new ApiException(500, "STORAGE_ROOT_UNAVAILABLE", "Nao foi possivel gravar o objeto.");
		}
		Instant now = clock.instant();
		if (!catalog.markStored(object.id(), measured.bytes(), measured.sha256(), now)) {
			deleteQuietly(destination);
			throw new ApiException(409, "STORAGE_ALREADY_STORED", "O objeto ja tem copia confirmada.");
		}
		// A réplica 0 é a do Control Plane — a mesma convenção do upload pelo nó.
		catalog.savePlacement(new StoragePlacement(object.id(), 0, "control-plane", measured.bytes(),
			measured.sha256(), StoragePlacement.State.OK, now));
		designReplicas(object.id(), measured.bytes(), measured.sha256(), now);
		audit.record(actorId, "STORAGE_OBJECT_STORED", object.id(),
			"{\"bucket\":\"" + escape(object.bucket()) + "\",\"key\":\"" + escape(object.key()) + "\",\"bytes\":"
				+ measured.bytes() + ",\"sha256\":\"" + measured.sha256() + "\",\"via\":\"s3\"}");
		return catalog.findById(object.id()).orElse(object);
	}

	/** A política do bucket, ou os padrões quando ninguém configurou. */
	public StorageBucketPolicy bucketPolicy(String bucket) {
		validateBucket(bucket);
		return catalog.bucketPolicy(bucket).orElseGet(() -> StorageBucketPolicy.padrao(bucket));
	}

	/**
	 * Configura o ciclo de vida do bucket (brief §14). Retenção e expiração são validadas aqui, não
	 * só no banco: a <code>check</code> do banco devolve erro de SQL, e o cliente precisa de um 400
	 * com o campo errado.
	 */
	@Transactional
	public StorageBucketPolicy putBucketPolicy(String actorId, String bucket, boolean versioning,
			Integer retainSupersededDays, Integer expireAfterDays) {
		validateBucket(bucket);
		if (retainSupersededDays != null && retainSupersededDays < 0) {
			throw new ApiException(400, "STORAGE_POLICY_INVALID", "A retencao nao pode ser negativa.");
		}
		if (expireAfterDays != null && expireAfterDays <= 0) {
			throw new ApiException(400, "STORAGE_POLICY_INVALID", "A expiracao precisa ser de ao menos um dia.");
		}
		if (!versioning && retainSupersededDays != null) {
			// Retenção existe para versão substituída. Guardar o número num bucket sem versionamento
			// seria configurar algo que nunca vai agir — aceitar calado é o pior dos dois mundos.
			throw new ApiException(400, "STORAGE_POLICY_INVALID",
				"Retencao so faz sentido em bucket com versionamento.");
		}
		Instant now = clock.instant();
		StorageBucketPolicy saved = catalog.saveBucketPolicy(new StorageBucketPolicy(bucket, versioning,
			retainSupersededDays, expireAfterDays, actorId, now));
		audit.record(actorId, "STORAGE_BUCKET_POLICY", bucket,
			"{\"versioning\":" + versioning + ",\"retainSupersededDays\":" + json(retainSupersededDays)
				+ ",\"expireAfterDays\":" + json(expireAfterDays) + "}");
		return saved;
	}

	/** O histórico da chave, da versão mais antiga para a mais nova. */
	public List<StorageObject> versions(String bucket, String key) {
		validateBucket(bucket);
		validateKey(key);
		return catalog.versions(bucket, key);
	}

	/**
	 * Volta a chave para uma versão anterior: a versão escolhida vira uma <b>versão nova</b>, com
	 * conteúdo copiado da antiga, e a versão que estava na frente é substituída.
	 *
	 * <p>Não há "des-substituir" porque isso mudaria o passado: quem baixou a versão corrente
	 * continua com o que baixou, e o histórico segue contando o que aconteceu. Restaurar é uma
	 * gravação nova, com o conteúdo da antiga.</p>
	 */
	@Transactional
	public StorageObject restore(String actorId, String bucket, String key, long version) {
		StorageObject old = catalog.versions(bucket, key).stream().filter(o -> o.version() == version).findFirst()
			.orElseThrow(() -> new ApiException(404, "STORAGE_VERSION_UNKNOWN",
				"Esta chave nao tem a versao pedida."));
		if (!old.servable()) {
			throw new ApiException(409, "STORAGE_VERSION_NOT_SERVABLE",
				"Esta versao nao tem copia conferida para restaurar.");
		}
		if (old.currentVersion() && old.state() != StorageObjectState.DELETED) {
			throw new ApiException(409, "STORAGE_VERSION_CURRENT", "Esta e a versao corrente da chave.");
		}
		Instant now = clock.instant();
		long next = catalog.versions(bucket, key).stream().mapToLong(StorageObject::version).max().orElse(0L) + 1;
		String id = OBJECT_ID_PREFIX + NodeIds.next("");
		Optional<StorageObject> current = catalog.find(bucket, key);
		if (current.isPresent() && current.get().currentVersion() && current.get().state() != StorageObjectState.DELETED
				&& !catalog.markSuperseded(bucket, key, id, now)) {
			throw new ApiException(409, "STORAGE_VERSION_RACE", "A chave mudou durante a restauracao.");
		}
		catalog.insertVersion(id, bucket, key, old.contentType(), actorId, now, next);
		// O conteúdo é o da versão antiga, e o conferido é o medido nela: a restauração não relê o
		// disco, então o digest que vale é o que o catálogo já guardava.
		if (!catalog.markStored(id, old.bytes(), old.sha256(), now)) {
			throw new ApiException(409, "STORAGE_VERSION_RACE", "A chave mudou durante a restauracao.");
		}
		catalog.setExpiresAt(id, bucketPolicy(bucket).expiresAtFor(now));
		audit.record(actorId, "STORAGE_VERSION_RESTORED", id,
			"{\"bucket\":\"" + escape(bucket) + "\",\"key\":\"" + escape(key) + "\",\"restoredFrom\":"
				+ version + ",\"version\":" + next + "}");
		return catalog.findById(id).orElseThrow(() -> new ApiException(500, "STORAGE_VERSION_LOST",
			"A versao restaurada desapareceu do catalogo."));
	}

	private static String json(Integer value) {
		return value == null ? "null" : value.toString();
	}

	/**
	 * Grava o corpo e mostra o que foi gravado: bytes e SHA-256 calculados ao atravessar o fluxo.
	 *
	 * <p>O corpo é lido uma vez. Não há cópia em memória do objeto inteiro — um arquivo de 5 GB
	 * passaria pelo fluxo em pedaços e, se exceder o teto, o resto é descartado sem gravar.</p>
	 */
	@Transactional
	public Stored store(NetworkNode node, String objectId, InputStream body) {
		requireStorage(node);
		StorageObject object = catalog.findById(objectId)
			.orElseThrow(() -> new ApiException(404, "STORAGE_OBJECT_UNKNOWN", "Objeto nao encontrado no catalogo."));
		if (object.state() != StorageObjectState.PENDING) {
			throw new ApiException(409, "STORAGE_ALREADY_STORED", "O objeto ja tem copia confirmada.");
		}
		// O disco livre MEDIDO antes do corpo atravessar: um nó que declarou 2 TB e tem 40 GB passa
		// na checagem do declarado e enche o disco no meio do arquivo — o carregador fica com um
		// arquivo pela metade e as tarefas seguintes falham por um espaço que já se sabia escasso.
		// Recusar antes é o mesmo critério do teto de tamanho: não escrever o que se sabe que não
		// cabe. Sem medida (runtime antigo) não há o que comparar e o caminho segue como antes.
		// Isto é o disco da máquina. Abaixo, a recusa pelo que ESTE recebedor cedeu: são duas
		// réguas e a menor das duas manda, porque declarar 1,5 TB num disco de 2 TB com 500 GB de
		// uso alheio não é espaço livre para o Énóis.
		if (node.observedDiskFreeMb() != null && node.observedDiskFreeMb() < MIN_DISK_FREE_MB) {
			throw new ApiException(507, "STORAGE_NO_DISK_SPACE",
				"O no informa " + node.observedDiskFreeMb() + " MB livres, abaixo do minimo de "
					+ MIN_DISK_FREE_MB + " MB para receber objeto.");
		}
		// O que o Énóis já ocupa aqui, pelo MAIOR entre o confirmado no catálogo e o medido pelo nó:
		// os dois são limites de baixo e usar o menor esconderia o que um deles já viu ocupado.
		long confirmadoMb = 0;
		for (StoragePlacement placement : catalog.placementsOnNode(node.id())) {
			if (placement.state() == StoragePlacement.State.OK) {
				confirmadoMb += (placement.bytes() / (1024 * 1024)) + 1;
			}
		}
		long usadoMb = node.observedContentUsedMb() == null ? 0 : node.observedContentUsedMb();
		long ocupadoMb = Math.max(confirmadoMb, usadoMb);
		// O teto só existe quando o participante declarou: declarado use zero para dizer "nada"
		// seria recusar toda gravação a quem só não informou, e é o cadastro do enroll (V305).
		if (node.declaredDiskMb() > 0 && node.declaredDiskMb() - ocupadoMb < MIN_DISK_FREE_MB) {
			throw new ApiException(507, "STORAGE_NO_CEDENT_SPACE",
				"O no cedeu " + node.declaredDiskMb() + " MB e ja ocupa " + ocupadoMb
					+ " MB, abaixo do minimo de " + MIN_DISK_FREE_MB + " MB de folga para receber objeto.");
		}
		Spooled measured = measure(body);
		if (measured.bytes() == 0) {
			deleteQuietly(measured.temp());
			throw new ApiException(400, "STORAGE_EMPTY_BODY", "O corpo enviado esta vazio.");
		}
		if (measured.bytes() > maxObjectBytes) {
			deleteQuietly(measured.temp());
			throw new ApiException(413, "STORAGE_OBJECT_TOO_LARGE",
				"O objeto excede o teto desta instalacao (" + maxObjectBytes + " bytes).");
		}
		// O arquivo vai para o destino ANTES do catálogo: registrar "tem cópia" apontando para um
		// arquivo que ainda não existe daria um objeto servível sem bytes.
		Path destination = bodyPath(object.id());
		try {
			if (envelope == null) {
				Files.move(measured.temp(), destination, StandardCopyOption.REPLACE_EXISTING);
			}
			else {
				// O digest medido é do TEXTO CLARO, e continua sendo: o catálogo promete o sha-256 do
				// que o cliente mandou, não do que está no disco. O arquivo é cifrado com a DEK deste
				// objeto; o texto claro só existe no temporário, que sai logo abaixo.
				envelope.encryptTo(measured.temp(), destination, root());
				deleteQuietly(measured.temp());
			}
		}
		catch (IOException unwritable) {
			deleteQuietly(measured.temp());
			throw new ApiException(500, "STORAGE_ROOT_UNAVAILABLE", "Nao foi possivel gravar o objeto.");
		}
		Instant now = clock.instant();
		if (!catalog.markStored(object.id(), measured.bytes(), measured.sha256(), now)) {
			// Corrida: outra requisição confirmou o objeto entre a leitura e o update. A transição é
			// condicional no estado, então o perdedor descobre aqui em vez de sobrescrever o medido.
			// O arquivo recém-escrito sai: quem venceu gravou o dele no mesmo caminho.
			deleteQuietly(destination);
			throw new ApiException(409, "STORAGE_ALREADY_STORED", "O objeto ja tem copia confirmada.");
		}
		catalog.savePlacement(new StoragePlacement(object.id(), 0, node.id(), measured.bytes(), measured.sha256(),
			StoragePlacement.State.OK, now));
		// A replica 0 e a do Control Plane. As demais sao DESIGNACAO, nao copia: o objeto entra no
		// catalogo do no escolhido como PENDING_REMOTE e so vira OK quando o no confirmar o que
		// gravou (markReplicaStored). Contar a designacao como durabilidade seria dizer que o
		// objeto esta em tres lugares quando esta em um e dois prometeram.
		designReplicas(object.id(), measured.bytes(), measured.sha256(), now);
		audit.record(node.id(), "STORAGE_OBJECT_STORED", object.id(),
			"{\"bucket\":\"" + escape(object.bucket()) + "\",\"key\":\"" + escape(object.key()) + "\",\"bytes\":"
				+ measured.bytes() + ",\"sha256\":\"" + measured.sha256() + "\"}");
		return new Stored(object.id(), measured.bytes(), measured.sha256());
	}

	/**
	 * A réplica confirma a cópia: o nó que recebeu um objeto designado avisa que gravou. Só vale se
	 * a colocação daquele nó existir e estiver {@code PENDING_REMOTE} — confirmação de cópia que
	 * ninguém designou é um nó dizendo que tem um arquivo que o catálogo não pediu.
	 *
	 * <p>O que o nó declara (sha-256 e tamanho) é <b>conferido contra o medido no Control Plane</b>.
	 * Divergência é cópia corrompida: a linha fica {@code STALE} e responde 409, em vez de vender
	 * uma cópia íntegra que tem outro conteúdo.</p>
	 */
	public StoragePlacement confirmReplica(NetworkNode node, String objectId, long bytes, String sha256) {
		requireStorage(node);
		StorageObject object = catalog.findById(objectId)
			.orElseThrow(() -> new ApiException(404, "STORAGE_OBJECT_UNKNOWN", "Objeto nao encontrado no catalogo."));
		StoragePlacement placement = catalog.placementOn(objectId, node.id())
			.orElseThrow(() -> new ApiException(403, "STORAGE_REPLICA_NOT_DESIGNATED",
				"Este no nao foi designado para este objeto."));
		if (placement.state() != StoragePlacement.State.PENDING_REMOTE) {
			throw new ApiException(409, "STORAGE_REPLICA_ALREADY_CONFIRMED",
				"Esta copia ja foi confirmada ou nao esta mais designada.");
		}
		if (object.sha256() == null || !object.sha256().equalsIgnoreCase(sha256) || object.bytes() != bytes) {
			// A marcacao de STALE tem de sobreviver ao 409. Com a confirmacao dentro de uma transacao,
			// o rollback desfazia esta gravacao e a linha voltava a PENDING_REMOTE: o no que gravou
			// lixo nunca aparecia como STALE para a varredura, e a divergencia ficava invisivel
			// justamente no registro dela. Por isso confirmReplica NAO e transacional: cada escrita
			// aqui e uma instrucao unica, e o que precisa persistir persistir antes do 409.
			marcarDivergente(objectId, placement, node.id(), bytes, sha256);
			throw new ApiException(409, "STORAGE_REPLICA_MISMATCH",
				"A copia declarada nao confere com o medido no Control Plane.");
		}
		Instant now = clock.instant();
		StoragePlacement confirmed = new StoragePlacement(objectId, placement.replica(), node.id(), bytes,
			object.sha256(), StoragePlacement.State.OK, now);
		catalog.savePlacement(confirmed);
		audit.record(node.id(), "STORAGE_REPLICA_CONFIRMED", objectId,
			"{\"replica\":" + placement.replica() + ",\"bytes\":" + bytes + "}");
		return confirmed;
	}

	/**
	 * O nó relê o próprio disco e devolve o que mediu: a lista de cópias suas que <b>não</b> conferem
	 * com o catálogo.
	 *
	 * <p>É o fecho do último ponto cego da varredura. A varredura do Control Plane só relê o disco
	 * <b>desta</b> instalação (§5.7): as cópias dos demais nós só podem ser conferidas pelo próprio
	 * nó, que é o único que tem acesso ao disco delas (NAT — o brief proíbe cadastrar endereço).</p>
	 *
	 * <p>O nó declara o medido; o Control Plane <b>confere</b> contra a colocação e só marca as
	 * cópias que <b>são</b> dele. Um nó não rebaixa a cópia de outro: a lista é filtrada por
	 * {@code node.id()} antes de qualquer escrita. O estado vai para {@code STALE} pelo mesmo caminho
	 * da confirmação divergente — a ação é a mesma (re-replicar, nunca apagar).</p>
	 *
	 * <p>Idempotente: cópia já {@code STALE} ou {@code GONE} não é regravada nem re-auditada. Um nó
	 * que reenvia o mesmo relatório não infla a auditoria nem muda o estado de novo.</p>
	 *
	 * @return quantas cópias foram marcadas {@code STALE} nesta chamada
	 */
	public int reportDirtyReplicas(NetworkNode node, List<Divergente> declaradas) {
		requireStorage(node);
		if (declaradas == null) {
			return 0;
		}
		int marcadas = 0;
		for (Divergente declarada : declaradas) {
			if (declarada == null || declarada.objectId() == null || declarada.objectId().isBlank()) {
				continue;
			}
			// Só a cópia DESTE nó. O filtro por dono é o que impede um nó de rebaixar a cópia de
			// outro: sem ele, a lista de entradas de qualquer nó serviria para derrubar o cluster.
			StoragePlacement minha = catalog.placementOn(declarada.objectId(), node.id()).orElse(null);
			if (minha == null || minha.state() != StoragePlacement.State.OK) {
				continue;
			}
			// O nó declara o que mediu; o digest esperado vem do catálogo, nunca do relatório. Um
			// nó não escolhe contra o que é comparado.
			marcarDivergente(declarada.objectId(), minha, node.id(), minha.bytes(),
				declarada.actualSha256() == null ? "" : declarada.actualSha256());
			marcadas++;
		}
		return marcadas;
	}

	/** O que um nó mediu no próprio disco e que não confere com o catálogo. */
	public record Divergente(String objectId, String actualSha256) {
	}

	/**
	 * Grava a cópia como divergente numa transação <b>própria</b> ({@code REQUIRES_NEW}): o chamador
	 * lança logo em seguida, e sem transação separada o rollback apagaria este registro. É a mesma
	 * razão pela qual o desfecho de tarefa do módulo grava antes de responder erro — o que deu errado
	 * precisa ficar registrado mesmo quando a operação falha.
	 */
	private void marcarDivergente(String objectId, StoragePlacement placement, String nodeId, long bytes,
			String sha256) {
		Instant now = clock.instant();
		catalog.savePlacement(new StoragePlacement(objectId, placement.replica(), nodeId, bytes, sha256,
			StoragePlacement.State.STALE, now));
		audit.record(nodeId, "STORAGE_REPLICA_MISMATCH", objectId,
			"{\"declaredSha256\":\"" + escape(sha256) + "\",\"declaredBytes\":" + bytes + "}");
	}

	/**
	 * Registra quem deve receber cópia do objeto. Não falha quando não há nó adequado: o objeto já
	 * está gravado e servível com uma cópia, e a réplica que falta é uma pendência de durabilidade,
	 * não um erro do upload. A colocação fica visível em {@code /placements} como PENDING_REMOTE,
	 * e é essa linha que uma varredura ou uma retomada encontra.
	 */
	private void designReplicas(String objectId, long bytes, String sha256, Instant now) {
		List<NetworkNode> chosen = placement.chooseReplicas(objectId, bytes, now);
		int replica = 1;
		for (NetworkNode node : chosen) {
			catalog.savePlacement(new StoragePlacement(objectId, replica++, node.id(), bytes, sha256,
				StoragePlacement.State.PENDING_REMOTE, now));
			audit.record("system", "STORAGE_REPLICA_DESIGNATED", objectId,
				"{\"node\":\"" + escape(node.id()) + "\",\"bytes\":" + bytes + "}");
		}
	}

	/**
	 * O objeto só é servível com cópia conferida; {@code PENDING} e vencido respondem 404 como se não
	 * existisse.
	 *
	 * <p>O vencido vale aqui <b>na leitura</b>, e não só quando a varredura passa: o brief §14 pede
	 * expiração automática, e um arquivo continuar servível entre o vencimento e a próxima varredura
	 * significa que "expirou" era só um campo na tabela.</p>
	 */
	public StorageObject requireDownloadable(String bucket, String key) {
		return requireDownloadable(bucket, key, "GET");
	}

	/**
	 * O mesmo caminho de leitura com o verbo que o chamador usa. HEAD não tem corpo, mas tem o custo de
	 * achar o objeto e de conferir a serventia — contá-lo como GET faria o painel mostrar leitura de
	 * arquivo onde houve só conferência de existência.
	 */
	public StorageObject requireDownloadable(String bucket, String key, String operacao) {
		long inicio = System.nanoTime();
		try {
			StorageObject object = doRequireDownloadable(bucket, key);
			conta(operacao, StorageOperationMetrics.Resultado.OK, inicio);
			return object;
		}
		catch (RuntimeException recusado) {
			conta(operacao, resultadoDe(recusado), inicio);
			throw recusado;
		}
	}

	private StorageObject doRequireDownloadable(String bucket, String key) {
		StorageObject object = catalog.find(bucket, key)
			.orElseThrow(() -> new ApiException(404, "STORAGE_NOT_FOUND", "Objeto nao encontrado."));
		if (!object.servable() || object.expired(clock.instant())) {
			throw new ApiException(404, "STORAGE_NOT_FOUND", "Objeto nao encontrado.");
		}
		return object;
	}

	/** Os nós que hoje têm uma cópia servível — a localização que o brief quer sem IP nem hostname. */
	public List<StoragePlacement> location(String objectId) {
		return catalog.placements(objectId).stream().filter(p -> p.state() == StoragePlacement.State.OK).toList();
	}

	/** Listagem de um bucket por prefixo, com paginação por chave. */
	public List<StorageObject> list(String bucket, String prefix, String afterKey, int limit) {
		long inicio = System.nanoTime();
		try {
			validateBucket(bucket);
			int bounded = Math.max(1, Math.min(limit <= 0 ? MAX_LIST_LIMIT : limit, MAX_LIST_LIMIT));
			List<StorageObject> achados = catalog.list(bucket, prefix, afterKey, bounded);
			conta("LIST", StorageOperationMetrics.Resultado.OK, inicio);
			return achados;
		}
		catch (RuntimeException recusado) {
			conta("LIST", resultadoDe(recusado), inicio);
			throw recusado;
		}
	}

	/** Lápide: idempotente, auditada, e não apaga a linha — as cópias nos nós ainda precisam ser coletadas. */
	@Transactional
	public boolean delete(String actorId, String bucket, String key) {
		long inicio = System.nanoTime();
		try {
			boolean removido = doDelete(actorId, bucket, key);
			conta("DELETE", StorageOperationMetrics.Resultado.OK, inicio);
			return removido;
		}
		catch (RuntimeException recusado) {
			conta("DELETE", resultadoDe(recusado), inicio);
			throw recusado;
		}
	}

	private boolean doDelete(String actorId, String bucket, String key) {
		StorageObject object = catalog.find(bucket, key)
			.orElseThrow(() -> new ApiException(404, "STORAGE_NOT_FOUND", "Objeto nao encontrado."));
		Instant now = clock.instant();
		boolean removed = catalog.markDeleted(object.id(), now);
		if (removed) {
			audit.record(actorId, "STORAGE_OBJECT_DELETED", object.id(),
				"{\"bucket\":\"" + escape(object.bucket()) + "\",\"key\":\"" + escape(object.key()) + "\"}");
		}
		return removed;
	}

	/** Um nó só grava objeto se carrega o perfil de storage — disco não dá permissão. */
	private static void requireStorage(NetworkNode node) {
		if (node.status() != NodeStatus.ACTIVE) {
			throw new ApiException(403, "NODE_NOT_OPERABLE", "O no precisa estar ativo.");
		}
		if (!TaskType.OBJECT_PUT.allowedFor(node.profiles())) {
			throw new ApiException(403, "NODE_PROFILE_REQUIRED", "O no nao tem o perfil de armazenamento.");
		}
	}

	/**
	 * A chave S3 não é normalizada: é byte a byte o que o cliente mandou. O que se valida é o que o
	 * contrato S3 proíbe (vazio, longo demais, barra inicial) — mudar o nome seria devolver um
	 * objeto diferente do que o cliente pediu para guardar.
	 */
	private static void validateKey(String key) {
		if (key == null || key.isBlank()) {
			throw new ApiException(400, "STORAGE_KEY_INVALID", "A chave do objeto e obrigatoria.");
		}
		// 700 é o teto da coluna (V319): o índice composto com o bucket tem de caber nos 3072 bytes
		// do InnoDB. Recusar aqui dá um erro claro em vez de um truncamento silencioso no banco.
		if (key.length() > 700) {
			throw new ApiException(400, "STORAGE_KEY_INVALID", "A chave do objeto e longa demais.");
		}
		if (key.startsWith("/")) {
			throw new ApiException(400, "STORAGE_KEY_INVALID", "A chave do objeto nao comeca com barra.");
		}
	}

	private static void validateBucket(String bucket) {
		if (bucket == null || bucket.isBlank() || bucket.length() > 63) {
			throw new ApiException(400, "STORAGE_BUCKET_INVALID", "Nome de bucket invalido.");
		}
		if (!bucket.equals(bucket.toLowerCase(java.util.Locale.ROOT))) {
			// S3 proíbe maiúscula em bucket: aceitar aqui daria duas grafias para o mesmo bucket, e a
			// única de `bucket` na tabela passaria a esconder colisão.
			throw new ApiException(400, "STORAGE_BUCKET_INVALID", "O nome do bucket deve ser minusculo.");
		}
	}

	/**
	 * Grava o corpo num temporário e o devolve medido. O leitor não guarda o objeto inteiro em
	 * memória: 5 GB passariam em pedaços de 64 KiB. O temporário só vira o arquivo do objeto se
	 * tudo der certo — upload interrompido deixa lixo apagável, nunca um objeto pela metade.
	 */
	private Spooled measure(InputStream body) {
		MessageDigest digest = sha256();
		long total = 0;
		Path temp;
		try {
			temp = Files.createTempFile(root(), ".upload-", ".part");
		}
		catch (IOException unusable) {
			throw new ApiException(500, "STORAGE_ROOT_UNAVAILABLE", "Diretorio de armazenamento indisponivel.");
		}
		byte[] buffer = new byte[64 * 1024];
		try (InputStream input = body; OutputStream sink = Files.newOutputStream(temp)) {
			int read;
			while ((read = input.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
				sink.write(buffer, 0, read);
				total += read;
			}
		}
		catch (IOException unreadable) {
			deleteQuietly(temp);
			throw new ApiException(400, "STORAGE_UPLOAD_FAILED", "Nao foi possivel ler o corpo enviado.");
		}
		return new Spooled(temp, total, HexFormat.of().formatHex(digest.digest()));
	}

	/**
	 * Mede um fluxo sem guardar nada: o sha-256 e o tamanho do que passou.
	 *
	 * <p>É o medidor da verificação por releitura — reler o corpo de uma cópia para conferir se ele
	 * ainda é o que o catálogo promete não pode escrever um segundo arquivo no disco de quem
	 * contribui. Mesmo algoritmo e mesmo pedaço de 64 KiB do {@link #measure}, porque medir de duas
	 * formas diferentes daria um digest que não bate com o guardado.</p>
	 */
	public static Measured measureStream(InputStream body) {
		MessageDigest digest = sha256();
		long total = 0;
		byte[] buffer = new byte[64 * 1024];
		try (InputStream input = body) {
			int read;
			while ((read = input.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
				total += read;
			}
		}
		catch (IOException unreadable) {
			throw new ApiException(400, "STORAGE_UPLOAD_FAILED", "Nao foi possivel ler o corpo enviado.");
		}
		return new Measured(total, HexFormat.of().formatHex(digest.digest()));
	}

	/** O que a releitura viu: tamanho e digest. Sem arquivo, sem temporário. */
	public record Measured(long bytes, String sha256) {
	}

	/** Apaga sem transformar erro de limpeza em erro da requisição: o lixo é recuperável, o upload não. */
	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException ignored) {
			// O temporário fica para a coleta do diretório; falhar aqui mudaria o erro que o cliente vê.
		}
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 ausente na JVM");
		}
	}

	/**
	 * O tempo medido vai para o painel. {@link System#nanoTime()} é monotônico: o relógio de parede pode
	 * saltar com NTP no meio da operação e uma latência negativa ou de horas apareceria no piso do pior
	 * caso do dia.
	 */
	private void conta(String operacao, StorageOperationMetrics.Resultado resultado, long inicioNanos) {
		if (metrics == null) {
			return;
		}
		metrics.record(operacao, resultado, Math.max(0, (System.nanoTime() - inicioNanos) / 1_000_000));
	}

	/**
	 * 4xx é o cliente que errou; 5xx é falha nossa. Separar os dois no painel é o que permite ler
	 * "a demanda é de clientes quebrados" e "o Storage está falhando" sem abrir log.
	 */
	private static StorageOperationMetrics.Resultado resultadoDe(RuntimeException erro) {
		if (erro instanceof ApiException api) {
			return api.status() >= 500 ? StorageOperationMetrics.Resultado.SERVER
				: StorageOperationMetrics.Resultado.CLIENT;
		}
		// Qualquer outra exceção não é pedido errado do cliente: é falha nossa.
		return StorageOperationMetrics.Resultado.SERVER;
	}

	/** JSON de auditoria: aspas e barra invertida da chave sao escapadas como no restante do modulo. */
	private static String escape(String raw) {
		return raw.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/** O temporário recém-gravado e o que foi medido nele. O declarado não é lido. */
	private record Spooled(Path temp, long bytes, String sha256) {
	}

	/** O resultado do PUT: as duas medidas que o cliente pode conferir com o que ele enviou. */
	public record Stored(String objectId, long bytes, String sha256) {
	}

	/** O objeto por id — o chamador já sabe que ele existe (ex.: veio de uma colocação). */
	/** O objeto pelo conteúdo medido, para a réplica que só conhece o digest (fase 3). */
	public Optional<StorageObject> byDigest(String sha256) {
		return catalog.findByDigest(sha256);
	}

	public Optional<StorageObject> byId(String id) {
		return catalog.findById(id);
	}

	/** As colocações deste nó, para ele reportar o que hospeda. */
	public List<StoragePlacement> placementsOn(String nodeId) {
		return catalog.placementsOnNode(nodeId);
	}

	/**
	 * O corpo do objeto para o download.
	 *
	 * <p>Fase 1: o Control Plane é o primeiro nó e guarda os bytes no disco dele (a fase 2 troca
	 * quem serve por uma designação de nó, mantendo o contrato). Falha ao abrir o arquivo é 410: o
	 * catálogo diz que existe e o arquivo não está lá — divergência de metadado, não "não existe".</p>
	 */
	public InputStream readBody(String objectId) {
		// O corpo é servido do disco DESTA instalação, nas duas formas que ela pode ter sem pedir
		// nada a outro nó:
		//   1. a raiz do Control Plane — onde o upload grava a réplica 0;
		//   2. o diretório de corpos do nó local (`rede.storage.node-body-root`) — onde o
		//      OBJECT_PUT deixa o corpo que este nó guarda.
		// Antes só a primeira existia: objeto replicado para o nó local respondia 409 mesmo com os
		// bytes a um diretório de distância, e "guardar no próprio disco" não servia para nada.
		Optional<StorageObject> object = catalog.findById(objectId);
		// A raiz SEM o `createDirectories` do `root()`: ler é leitura. Criar o diretório aqui faria
		// um GET num Control Plane que nunca recebeu upload escrever em disco, e fariam uma falha de
		// disco virar 500 num download em vez de "não está aqui" (409/410).
		Path cpBody = Path.of(storageRoot).resolve(objectId);
		boolean localCopy = nodeBodies != null && nodeBodies.has(objectId);
		if (Files.isReadable(cpBody)) {
			return openVerified(cpBody, object);
		}
		if (localCopy) {
			exigirCopiaServivel(objectId);
			// O `pathOf` + `openVerified` só reconhece o envelope (`ENOISENV1`); o arquivo que o
			// NodeBodyStore grava é `ENOISENC1`, outro cabeçalho. Servir o caminho cru devolveria os
			// bytes CIFRADOS com 200 — o cliente receberia bytes que só quem tem a chave entende.
			// Quem sabe decifrar o corpo do nó é o próprio NodeBodyStore.
			return openVerifiedDecifrado(nodeBodies, object);
		}
		// Nem aqui nem ali: continua sendo 409 com o digest, para o chamador designar a réplica que
		// tem o objeto. Entregar bytes de um arquivo que se sabe ausente seria a pior forma de
		// falha: um download que responde 200 e entrega nada (ou lixo) só aparece muito depois.
		if (object.isPresent() && object.get().sha256() != null) {
			throw new ApiException(409, "STORAGE_REPLICA_ELSEWHERE",
				"Esta copia nao esta neste no; use o digest " + object.get().sha256()
					+ " para identifica-la numa replica.");
		}
		throw new ApiException(410, "STORAGE_BODY_MISSING",
			"O catalogo registra a copia, mas o corpo nao esta neste no.");
	}

	/**
	 * Serve só a cópia que o catálogo ainda considera <b>boa</b>.
	 *
	 * <p><b>Antes:</b> a leitura local olhava só o arquivo. Um nó marcado {@code STALE} — pela
	 * confirmação com digest errado, pela varredura do próprio nó ou pela poda de designação vencida —
	 * continuava servindo o corpo para qualquer download ali, e o download é como a réplica de outro
	 * nó recebe o objeto. O catálogo dizia "não confere" e esta rota entregava o que o catálogo nega.</p>
	 *
	 * <p><b>Agora:</b> quando o corpo é a cópia de um nó ({@code NodeBodyStore} com dono identificado),
	 * a leitura exige linha {@code OK} para esse nó. Sem linha — objeto que nunca foi designado a ele — a
	 * leitura local segue valendo: é o caso do Control Plane que recebeu o upload e guarda o corpo sem
	 * passar por colocação de nó. A réplica 0 na raiz do CP continua fora desta regra: ela não tem
	 * linha de colocação de nó, e o CP é o próprio dono do disco.</p>
	 */
	private void exigirCopiaServivel(String objectId) {
		if (nodeBodies == null) {
			return;
		}
		String dono = nodeBodies.ownerNodeId();
		if (dono == null) {
			// Disco sem identidade de nó (instalação do CP, não um runtime): não há colocação a cobrar.
			return;
		}
		StoragePlacement copia = catalog.placementOn(objectId, dono).orElse(null);
		if (copia != null && copia.state() != StoragePlacement.State.OK) {
			throw new ApiException(409, "STORAGE_REPLICA_STALE",
				"A copia neste no nao confere (" + copia.state().name() + "); use uma copia OK.");
		}
	}

	/**
	 * O corpo da <b>réplica 0</b> — o arquivo da raiz deste Control Plane, <b>sem</b> cair para o
	 * diretório do nó local.
	 *
	 * <p>É o que a varredura relê. Se ela usasse {@link #readBody}, achar o corpo no nó local faria
	 * o sumiço da réplica 0 passar em silêncio: o relatório diria "conferido" sobre um arquivo que
	 * o catálogo não atribui a esta instalação.</p>
	 */
	public InputStream openReplicaZeroBody(String objectId) {
		return openVerified(bodyPath(objectId), catalog.findById(objectId));
	}

	/**
	 * O corpo guardado por <b>este</b> nó, no disco dele ({@code rede.storage.node-body-root}),
	 * <b>sem</b> cair para a raiz do Control Plane.
	 *
	 * <p>É o que a varredura relê quando a cópia pertence ao nó local. Sem ele, a varredura só
	 * conferia a réplica 0: um arquivo trocado no diretório do nó continuava contando como cópia
	 * confirmada, e o catálogo prometia durabilidade que não tinha.</p>
	 *
	 * <p>Falha ao abrir é 410, como na réplica 0: o catálogo diz que existe e o disco não tem —
	 * divergência de metadado, não "não existe".</p>
	 */
	public InputStream openNodeBody(String objectId) {
		if (nodeBodies == null || !nodeBodies.has(objectId)) {
			throw new ApiException(410, "STORAGE_BODY_MISSING",
				"O catalogo registra a copia neste no, mas o corpo nao esta no disco dele.");
		}
		return openVerified(nodeBodies.pathOf(objectId), catalog.findById(objectId));
	}

	/**
	 * Abre o corpo da réplica local pelo leitor do {@code NodeBodyStore}, conferindo o tamanho do que
	 * ele <b>entrega</b> — já decifrado.
	 *
	 * <p>A conferência é sobre o fluxo decifrado, não sobre o arquivo: o arquivo cifrado é maior que o
	 * objeto (cabeçalho `ENOISENC1` + tag), então medir o arquivo daria 410 em toda réplica cifrada.
	 * Aqui o tamanho medido é o do texto claro, que é justamente o que o catálogo promete.</p>
	 */
	private InputStream openVerifiedDecifrado(NodeBodyStore store, Optional<StorageObject> object) {
		InputStream clear;
		try {
			clear = store.read(object.map(StorageObject::id).orElse(null));
		}
		catch (IOException unreadable) {
			throw new ApiException(410, "STORAGE_BODY_MISSING",
				"O catalogo registra a copia, mas o corpo nao esta neste no.");
		}
		Long expected = object.map(StorageObject::bytes).orElse(null);
		if (expected == null) {
			return clear;
		}
		// Confere o tamanho ANTES de entregar o fluxo, sem reter o objeto: o arquivo local já está no
		// disco, então a conferência é o tamanho medido no disco MENOS o que o cabeçalho cifrado e a
		// tag do GCM acrescentam — do mesmo jeito que o caminho do envelope confere o arquivo. O que
		// percorre o fluxo e segura o objeto na memória não é opção: o teto é 5 GiB.
		try {
			long textoClaro = store.plainBytesOf(object.map(StorageObject::id).orElse(null));
			if (textoClaro != expected) {
				throw new ApiException(410, "STORAGE_BODY_SIZE_MISMATCH",
					"O corpo tem " + textoClaro + " bytes, mas o catalogo registra " + expected + ".");
			}
		}
		catch (IOException semTamanho) {
			throw new ApiException(410, "STORAGE_BODY_MISSING",
				"O catalogo registra a copia, mas o corpo nao esta neste no.");
		}
		// O contador fica de guarda mesmo assim: se o arquivo tiver o tamanho certo mas a decifra
		// entregar mais que o prometido, o cliente não recebe o excedente.
		return new TamanhoConferido(clear, expected);
	}

	private static final class TamanhoConferido extends java.io.FilterInputStream {

		private final long limite;
		private long entregues;

		TamanhoConferido(InputStream fonte, long limite) {
			super(fonte);
			this.limite = limite;
		}

		@Override
		public int read() throws IOException {
			int byteLido = super.read();
			if (byteLido >= 0) {
				excedeu(1);
			}
			return byteLido;
		}

		@Override
		public int read(byte[] buffer, int inicio, int tamanho) throws IOException {
			int lidos = super.read(buffer, inicio, tamanho);
			if (lidos > 0) {
				excedeu(lidos);
			}
			return lidos;
		}

		private void excedeu(int bytes) throws IOException {
			entregues += bytes;
			if (entregues > limite) {
				throw new IOException("corpo maior que os " + limite + " bytes registrados no catalogo");
			}
		}
	}

	/**
	 * Abre o corpo depois de conferir que ele tem o tamanho que o catálogo promete.
	 *
	 * <p>O download responde com o {@code Content-Length} medido no upload. Servir um arquivo
	 * truncado com esse comprimento é um 200 que se rompe no fim — e o cliente não tem como saber
	 * se perdeu bytes da rede ou do disco. Divergência de tamanho responde 410, com os dois números
	 * no erro; divergência de conteúdo com o mesmo tamanho é o que a varredura encontra pelo
	 * sha-256, não o caminho de download.</p>
	 */
	private InputStream openVerified(Path body, Optional<StorageObject> object) {
		try {
			long size = Files.size(body);
			Long expected = object.map(StorageObject::bytes).orElse(null);
			if (expected != null) {
				if (envelope != null && envelope.isEnvelope(body)) {
					// Corpo cifrado: o arquivo carrega cabeçalho do envelope + tag do GCM, e o tamanho
					// prometido é o do texto claro. Comparar os dois diretamente daria 410 em todo
					// objeto cifrado. O que se confere aqui é que o arquivo não é MENOR do que o
					// possível para o tamanho prometido: truncamento aparece, e a integridade real é a
					// tag do GCM, conferida ao fim do fluxo.
					long minimo = expected + EnvelopeBodyCrypto.headerBytes() + 16;
					if (size < minimo) {
						throw new ApiException(410, "STORAGE_BODY_SIZE_MISMATCH",
							"O corpo cifrado tem " + size + " bytes, menos que o minimo " + minimo
								+ " para o objeto de " + expected + " bytes.");
					}
				}
				else if (expected != size) {
					throw new ApiException(410, "STORAGE_BODY_SIZE_MISMATCH",
						"O corpo tem " + size + " bytes, mas o catalogo registra " + expected + ".");
				}
			}
			if (envelope != null) {
				// Envelope = DEK própria deste objeto. Arquivo em claro (gravado antes da chave)
				// continua legível: ligar o envelope não pode reprovar o que já existe.
				return envelope.isEnvelope(body) ? envelope.decrypt(body) : Files.newInputStream(body);
			}
			return Files.newInputStream(body);
		}
		catch (IOException unreadable) {
			throw new ApiException(410, "STORAGE_BODY_MISSING",
				"O catalogo registra a copia, mas o corpo nao esta neste no.");
		}
	}

	/** Onde este Control Plane guarda o corpo que ele mesmo recebeu (papel de primeiro nó, fase 1). */
	public Path bodyPath(String objectId) {
		// O id vem do catálogo, mas ainda assim é validado: um id com "/" ou ".." escreveria fora do
		// diretório autorizado, e o diretório de storage é do participante.
		if (!objectId.matches("[A-Za-z0-9_-]{1,40}")) {
			throw new ApiException(400, "STORAGE_OBJECT_ID_INVALID", "Identificador de objeto invalido.");
		}
		return root().resolve(objectId);
	}

	/** Diretório autorizado deste CP, criado na primeira escrita. */
	private Path root() {
		Path root = Path.of(storageRoot);
		try {
			return Files.createDirectories(root);
		}
		catch (IOException unusable) {
			throw new ApiException(500, "STORAGE_ROOT_UNAVAILABLE", "Diretorio de armazenamento indisponivel.");
		}
	}
}