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
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository.PaginaDeVersoes;
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
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

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

	private static final Logger LOG = LoggerFactory.getLogger(StorageCatalogService.class);

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
	 * O corpo que cada gravação em curso substituiu, por id de objeto. Existe só durante o
	 * `finishUpload`, até a gravação ser confirmada ou recusada: a recusa precisa dos bytes de antes,
	 * não só dos metadados.
	 */
	private final java.util.Map<String, Path> backups = new java.util.concurrent.ConcurrentHashMap<>();
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
	 * Cota de bytes por participante ({@code created_by}); {@code 0} desliga (doc 20 §10).
	 *
	 * <p>O teto de espaço que existia era o de quem <b>cede</b> (o nó). Sem a cota de quem
	 * <b>usa</b>, um participante com credencial S3 enche o disco e a cota dos nós, e o upload dos
	 * outros passa a responder {@code 507} — o vizinho paga pelo vizinho.</p>
	 */
	private final long maxBytesPerOwner;

	/**
	 * O que cada participante já ocupa, <b>nesta instância</b>, a partir do que o banco respondeu na
	 * primeira leitura. É cache, não fonte da verdade: a fonte é {@code bytesOwnedBy}.
	 */
	private final Map<String, Long> usadoPorDono = new java.util.concurrent.ConcurrentHashMap<>();
	/**
	 * O banco, para a única conta de cota que o catálogo não consegue fazer sozinho: o que o
	 * multipart tem <b>reservado</b> por conta, em partes abertas.
	 *
	 * <p>A abertura do upload precisa dessa conta: sem ela, quem já ocupou a cota em partes passava
	 * pela abertura e só descobria na conclusão (o ciclo 87 cobrou as partes no envio; o ciclo 89
	 * fecha a outra ponta). Nulo = instalação sem banco para essa conta (teste unitário), e aí o
	 * reservado é zero.</p>
	 */
	@Nullable
	private final JdbcTemplate jdbc;

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
	 * Com o contador de operação, sem cota de participante. A delegação acima mantém os testes de
	 * outros assuntos com a assinatura que já usavam; a cota fica no construtor de baixo.
	 */
	StorageCatalogService(StorageCatalogRepository catalog, AuditTrail audit, Clock clock,
			@Value("${rede.storage.max-object-bytes:5368709120}") long maxObjectBytes,
			@Value("${rede.storage.root:${java.io.tmpdir}/enois-storage}") String storageRoot,
			@Value("${rede.security.encryption-key:}") String encryptionKey,
			StoragePlacementService placement, @Nullable NodeBodyStore nodeBodies,
			@Nullable StorageOperationMetrics metrics) {
		this(catalog, audit, clock, maxObjectBytes, storageRoot, encryptionKey, placement, nodeBodies, metrics, 0L,
			null);
	}

	/** A forma com cota por participante — é a que o Spring injeta (doc 20 §10). */
	@Autowired
	StorageCatalogService(StorageCatalogRepository catalog, AuditTrail audit, Clock clock,
			@Value("${rede.storage.max-object-bytes:5368709120}") long maxObjectBytes,
			@Value("${rede.storage.root:${java.io.tmpdir}/enois-storage}") String storageRoot,
			@Value("${rede.security.encryption-key:}") String encryptionKey,
			StoragePlacementService placement, @Nullable NodeBodyStore nodeBodies,
			@Nullable StorageOperationMetrics metrics,
			@Value("${rede.storage.max-bytes-per-owner:0}") long maxBytesPerOwner,
			@Nullable JdbcTemplate jdbc) {
		this.maxBytesPerOwner = Math.max(0, maxBytesPerOwner);
		this.jdbc = jdbc;
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
		return beginUpload(actorId, bucket, key, contentType, true);
	}

	/**
	 * A abertura do upload, com a conta da cota <b>já feita por quem chamou</b>.
	 *
	 * <p>A multipart conclui passando {@code false}: as partes daquele upload <b>são</b> o corpo que
	 * está chegando. Cobrá-las outra vez na conclusão fazia um objeto de 4 partes acima do mínimo
	 * de 5 MiB cada (mais de 20 MiB — tudo o que o SDK manda) nunca concluir numa cota de 20 MiB,
	 * mesmo com o corpo já reservado. A conclusão cobra uma conta só: o corpo montado (<code>
	 * finishUpload</code>), que é o objeto que vai nascer.</p>
	 */
	@Transactional
	public StorageObject beginUpload(String actorId, String bucket, String key, String contentType,
			boolean cobrarPartesReservadas) {
		validateBucket(bucket);
		validateKey(key);
		String type = validatedContentType(contentType);
		Instant now = clock.instant();
		// A cota é cobrada <b>antes</b> de tudo: um objeto PENDING derrubado pela cota deixaria a
		// chave ocupada até virar lápide, e o upload seguinte do mesmo participante não poderia
		// reusar a chave.
		//
		// A conta inclui as partes de multipart abertas: quem já reservou espaço em partes não
		// passa por aqui só porque ainda não ganhou um byte no catálogo.
		exigirCotaDoParticipante(actorId, 0, cobrarPartesReservadas ? reservadoEmPartes(actorId) : 0);
		StorageBucketPolicy policy = bucketPolicy(bucket);

		if (policy.versioning()) {
			// Bucket com versionamento: cada upload é uma versão nova. A corrente anterior sai da
			// frente ANTES, porque o índice único condicional só aceita uma corrente por chave; se
			// outra gravação venceu a corrida, esta tenta de novo com o próximo número em vez de
			// derrubar a chave inteira.
			return beginVersionedUpload(actorId, bucket, key, type, now, policy);
		}

		// A chave única do banco é (bucket, object_key) — e a linha viva é a única com
		// `superseded_by is null`. O `createPending` é inserir-ou-ler, então uma chave que ainda tem
		// linha devolve a linha, qualquer que seja o estado. Duas armadilhas, e as duas davam falha
		// silenciosa:
		//
		//   - vencida que a coleta não passou: o `beginUpload` devolvia o objeto velho, com o prazo
		//     velho, e o `finishUpload` respondia 409 "já tem cópia" para um objeto que o download já
		//     tratava como 404;
		//   - lápide de remoção: o 409 chegava para o cliente S3, para quem o DELETE é só um nome livre
		//     de novo.
		//
		// O que sai da chave aqui não é lápide nova: nada que ainda tenha conteúdo é marcado como
		// removido por causa de uma abertura de upload que pode não virar gravação.
		//   - lápide existente (o cliente já removeu): a LINHA sai. Deixá-la como lápide aposentada a
		//     faria ser achada de novo pelo `delete`, que apagaria o objeto novo no lugar do dela — e o
		//     que ela guardava já está na cópia de cada nó, que é quem a coleta consulta;
		//   - vencida: sai aposentada (`superseded_by` apontando para a linha nova), porque vencimento
		//     não é remoção e a coleta precisa dela como expirada.
		// O insert-ou-ler deixaria de engolir a chave nos dois casos.
		StorageObject existente = catalog.find(bucket, key).orElse(null);
		if (existente != null && existente.state() == StorageObjectState.DELETED) {
			catalog.discardPending(existente.id());
			existente = null;
		}
		if (existente != null && existente.expired(now)) {
			// A versão vencida sai da chave sem virar lápide: vencimento não é remoção. Se fosse lápide,
			// o corpo de um objeto só vencido deixaria de ser servido por quem tem cópia, e a coleta o
			// trataria como removido em vez de expirado. O que ela aponta é a linha nova.
			//
			// A linha nova NASCE com o retrato da vencida (V367 e o conteúdo): o reenvio na mesma chave
			// é o caso normal, e abrir em branco fazia a gravação recusada, ao devolver o objeto, voltar
			// um objeto sem tipo, sem metadado e sem prazo — o dono perdia o que tinha declarado.
			String novoId = OBJECT_ID_PREFIX + NodeIds.next("");
			if (!catalog.markSuperseded(bucket, key, novoId, now)) {
				throw new ApiException(409, "STORAGE_VERSION_RACE",
					"A chave mudou durante a reabertura; tente novamente.");
			}
			// A versão corrente saiu da conta (`superseded_by is null`): a conta mudou de dono do
			// número, e o cache do antigo e do novo precisa reler.
			esquecerUsoDoParticipante(existente.createdBy());
			esquecerUsoDoParticipante(actorId);
			StorageObject aberta = catalog.createPending(novoId, bucket, key, type, actorId, now);
			catalog.setClientHeaders(aberta.id(), existente.metadata(), existente.cacheControl(),
				existente.contentDisposition());
			return catalog.findById(aberta.id()).orElse(aberta);
		}
		StorageObject object = catalog.createPending(OBJECT_ID_PREFIX + NodeIds.next(""), bucket, key, type,
			actorId, now);
		if (object.state() == StorageObjectState.DELETED) {
			// Corrida: a lápide apareceu entre a leitura e o insert-ou-ler, e o insert-ou-ler devolveu
			// a linha morta. Nada foi gravado, então a resposta pode ser a mesma de antes.
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
	 * O {@code Content-Type} declarado, ou o padrão quando veio vazio.
	 *
	 * <p><b>Antes:</b> o tipo era gravado como veio. Um {@code Content-Type} com {@code \r\n} ficava
	 * na linha e voltava no {@code HEAD}/{@code GET} como <b>dois</b> campos do cabeçalho — a injeção
	 * só não virava cabeçalho novo porque o Tomcat descarta o que não é nome de campo válido. Valor
	 * de qualquer tamanho também passava, e o tipo voltava inteiro na leitura.</p>
	 *
	 * <p><b>Agora:</b> a mesma regra do {@code Cache-Control}/{@code Content-Disposition}: cabe em
	 * {@link #MAX_CLIENT_HEADER} e não tem caractere de controle; senão {@code 400} antes de abrir
	 * upload. É o tipo que o cliente declarou, porque é ele que o navegador usa para decidir o que
	 * fazer com o arquivo (§4.50).</p>
	 */
	public static String validatedContentType(String contentType) {
		if (contentType == null || contentType.isBlank()) {
			return "application/octet-stream";
		}
		validateHeaderValue(contentType, "Content-Type");
		return contentType;
	}

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
			validateHeaderValue(valor, "Cache-Control e Content-Disposition");
		}
		return new ClientHeaders(metadata, cacheControl, contentDisposition);
	}

	/** Tamanho e caracteres de controle de um valor que volta como cabeçalho HTTP. */
	private static void validateHeaderValue(String valor, String nome) {
		if (valor.length() > MAX_CLIENT_HEADER) {
			throw new ApiException(400, "InvalidArgument",
				nome + " do objeto cabe em " + MAX_CLIENT_HEADER + " caracteres.");
		}
		for (int i = 0; i < valor.length(); i++) {
			if (valor.charAt(i) < 0x20 && valor.charAt(i) != '\t' || valor.charAt(i) == 0x7f) {
				throw new ApiException(400, "InvalidArgument",
					nome + " nao aceitam caractere de controle.");
			}
		}
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

	/**
	 * O que a linha <b>já tem</b> declarado: o metadado, o {@code Cache-Control} e o
	 * {@code Content-Disposition}.
	 *
	 * <p>É o que o {@code PUT} precisa <b>antes</b> de {@link #saveClientHeaders}: a gravação nova
	 * declara o que o cliente mandou, e a recusa por donagem tem de devolver o retrato de antes — o valor
	 * que estava na linha, e não o da requisição recusada.</p>
	 */
	public ClientHeaders declaredHeaders(StorageObject object) {
		return new ClientHeaders(object.metadata(), object.cacheControl(), object.contentDisposition());
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
			if (current.isPresent()) {
				esquecerUsoDoParticipante(current.get().createdBy());
			}
			esquecerUsoDoParticipante(actorId);
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
	public StorageObject finishUpload(String objectId, InputStream body, String actorId,
			boolean cobrarPartesReservadas) {
		long inicio = System.nanoTime();
		try {
			StorageObject gravado = doFinishUpload(objectId, body, actorId, cobrarPartesReservadas);
			conta("PUT", StorageOperationMetrics.Resultado.OK, inicio);
			return gravado;
		}
		catch (RuntimeException recusado) {
			conta("PUT", resultadoDe(recusado), inicio);
			throw recusado;
		}
	}

	/**
	 * O mesmo upload, com o corpo vazio permitido: é o caminho do <b>cliente S3</b>.
	 *
	 * <p>O S3 aceita objeto de 0 byte — é o marcador de "pasta" que {@code aws s3 cp --recursive}
	 * cria e o resultado de uma escrita vazia legítima. Recusá-lo fazia um cliente que sincroniza uma
	 * árvore inteira tratar a sincronização como falha em cada marcador, com todos os arquivos já
	 * gravados. A recusa continua valendo para o upload <b>do nó</b> ({@link #store}): ali o corpo
	 * vazio é sinal de gravação que não aconteceu.</p>
	 */
	public StorageObject finishUploadPermitindoVazio(String objectId, InputStream body, String actorId) {
		long inicio = System.nanoTime();
		try {
			StorageObject gravado = doFinishUpload(objectId, body, actorId, true, true);
			conta("PUT", StorageOperationMetrics.Resultado.OK, inicio);
			return gravado;
		}
		catch (RuntimeException recusado) {
			conta("PUT", resultadoDe(recusado), inicio);
			throw recusado;
		}
	}

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
		return doFinishUpload(objectId, body, actorId, true);
	}

	/**
	 * A gravação do corpo, com a conta da cota escolhida por quem chama.
	 *
	 * <p>{@code cobrarPartesReservadas=false} é a conclusão do multipart: as partes daquele upload
	 * <b>são</b> o corpo que está chegando, já cobradas no envio de cada uma. Somá-las de novo na
	 * conclusão fazia o objeto nunca nascer numa cota apertada, embora o espaço já estivesse
	 * reservado.</p>
	 */
	private StorageObject doFinishUpload(String objectId, InputStream body, String actorId,
			boolean cobrarPartesReservadas) {
		return doFinishUpload(objectId, body, actorId, cobrarPartesReservadas, false);
	}

	private StorageObject doFinishUpload(String objectId, InputStream body, String actorId,
			boolean cobrarPartesReservadas, boolean aceitaVazio) {
		StorageObject object = catalog.findById(objectId)
			.orElseThrow(() -> new ApiException(404, "STORAGE_OBJECT_UNKNOWN", "Objeto nao encontrado no catalogo."));
		if (object.state() != StorageObjectState.PENDING) {
			throw new ApiException(409, "STORAGE_ALREADY_STORED", "O objeto ja tem copia confirmada.");
		}
		Spooled measured = measure(body);
		if (measured.bytes() == 0 && !aceitaVazio) {
			deleteQuietly(measured.temp());
			throw new ApiException(400, "STORAGE_EMPTY_BODY", "O corpo enviado esta vazio.");
		}
		if (measured.bytes() > maxObjectBytes) {
			deleteQuietly(measured.temp());
			throw new ApiException(413, "STORAGE_OBJECT_TOO_LARGE",
				"O objeto excede o teto desta instalacao (" + maxObjectBytes + " bytes).");
		}
		try {
			// O corpo já foi medido: a cota cobra o tamanho, e não só o que já está gravado.
			exigirCotaDoParticipante(actorId, measured.bytes(),
				cobrarPartesReservadas ? reservadoEmPartes(actorId) : 0);
		}
		catch (RuntimeException semCota) {
			deleteQuietly(measured.temp());
			throw semCota;
		}
		Path destination = bodyPath(object.id());
		Path backup = guardarCorpoAnterior(destination);
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
			restaurarBackup(object.id(), backup, destination);
			throw new ApiException(500, "STORAGE_ROOT_UNAVAILABLE", "Nao foi possivel gravar o objeto.");
		}
		if (backup != null) {
			backups.put(object.id(), backup);
		}
		Instant now = clock.instant();
		if (!catalog.markStored(object.id(), measured.bytes(), measured.sha256(), now)) {
			deleteQuietly(destination);
			throw new ApiException(409, "STORAGE_ALREADY_STORED", "O objeto ja tem copia confirmada.");
		}
		// A réplica 0 é a do Control Plane — a mesma convenção do upload pelo nó.
		catalog.savePlacement(new StoragePlacement(object.id(), 0, "control-plane", measured.bytes(),
			measured.sha256(), StoragePlacement.State.OK, now));
		// A cota conta o que o participante tem: a gravação que passou aumenta a conta dele.
		somarUsoDoParticipante(actorId, measured.bytes());
		designReplicas(object.id(), measured.bytes(), measured.sha256(), now);
		audit.record(actorId, "STORAGE_OBJECT_STORED", object.id(),
			"{\"bucket\":\"" + escape(object.bucket()) + "\",\"key\":\"" + escape(object.key()) + "\",\"bytes\":"
				+ measured.bytes() + ",\"sha256\":\"" + measured.sha256() + "\",\"via\":\"s3\"}");
		// O backup NÃO sai aqui: esta gravação ainda pode ser recusada depois de confirmada (donagem),
		// e a recusa precisa dos bytes de antes. Quem solta é quem responde `200` (`soltarBackup`).
		return catalog.findById(object.id()).orElse(object);
	}

	/**
	 * Guardo o corpo que já estava na chave antes de a gravação o substituir.
	 *
	 * <p>Uma recusa depois da gravação (donagem, por exemplo) devolve o objeto ao que era; os
	 * metadados sozinhos não devolvem os bytes, porque o {@code GET} serve do disco. Devolve o caminho
	 * do backup, ou {@code null} quando não havia corpo ou ele não pôde ser movido.</p>
	 */
	private Path guardarCorpoAnterior(Path destination) {
		if (!Files.exists(destination)) {
			return null;
		}
		Path backup = Path.of(destination + ".antes-" + java.util.UUID.randomUUID().toString().replace("-", ""));
		try {
			Files.move(destination, backup, StandardCopyOption.ATOMIC_MOVE);
			return backup;
		}
		catch (IOException semBackup) {
			return null;
		}
	}

	/**
	 * Devolve o corpo que a gravação substituiu, se ela guardou um.
	 *
	 * <p>{@code restorePending} devolve os metadados, mas o {@code GET} serve do disco: sem os bytes de
	 * antes, o {@code HEAD} do dono volta certo (12 bytes) e o {@code GET} entrega os da gravação
	 * recusada (13). Chamado dentro de {@link #restorePending}; idempotente.</p>
	 */
	private void restaurarBackup(String objectId, Path backup, Path destination) {
		if (backup == null) {
			return;
		}
		try {
			Files.move(backup, destination, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException naoRestaurou) {
			// O backup fica no disco, com nome próprio: apagá-lo perderia o único corpo de antes. O
			// caminho fica no log para quem for recuperar à mão.
			LOG.warn("Corpo de antes nao voltou para `{}`; o backup esta em `{}`.", objectId, backup);
		}
	}

	/**
	 * Restaura o objeto ao retrato de antes, <b>incluindo os bytes</b>.
	 *
	 * <p>O {@code finishUpload} grava o arquivo antes de o catálogo confirmar; uma recusa depois disso
	 * (donagem) precisa devolver o corpo anterior. Os metadados sozinhos devolviam um objeto pela
	 * metade: {@code HEAD} com o tamanho antigo e {@code GET} com o conteúdo recusado.</p>
	 */
	public boolean restorePending(String id, String contentType, Long bytes, String sha256, ObjectMetadata metadata,
			String cacheControl, String contentDisposition, Instant expiresAt) {
		Path backup = backups.remove(id);
		if (backup != null) {
			restaurarBackup(id, backup, bodyPath(id));
		}
		return catalog.restorePending(id, contentType, bytes, sha256, metadata, cacheControl, contentDisposition,
			expiresAt);
	}

	/**
	 * Descarta o backup de uma gravação confirmada.
	 *
	 * <p>Chamado quando a resposta é `200`: o corpo novo é o que fica, e o de antes não serve mais. Sem
	 * isso, todo PUT sobre uma chave existente deixaria um arquivo `.antes-*` para trás.</p>
	 */
	public void soltarBackup(String objectId) {
		Path backup = backups.remove(objectId);
		if (backup != null) {
			deleteQuietly(backup);
		}
	}

	/**
	 * Cota de bytes por participante (brief §10, "quotas"; §7, "não permitir que o armazenamento do
	 * Énóis consuma espaço além do limite configurado").
	 *
	 * <p><b>Antes:</b> o teto de espaço existia do lado de quem <b>cede</b> (o nó: disco livre,
	 * espaço declarado, §7) e não do lado de quem <b>usa</b>. Um único participante com credencial S3
	 * enchia o disco, enchia a cota dos nós e os uploads dos outros passavam a responder
	 * {@code 507 STORAGE_NO_CEDENT_SPACE} — o vizinho pagava pelo vizinho.</p>
	 *
	 * <p><b>Agora:</b> o que {@code actorId} já tem no catálogo ({@code created_by}, somando só as
	 * versões correntes e sem as lápides) é comparado com {@code rede.storage.max-bytes-per-owner}.
	 * Estourado, a resposta é {@code 507 STORAGE_OWNER_QUOTA_EXCEEDED} com o número que acabou.</p>
	 *
	 * <p><b>O que este número não é:</b> a cota do <b>objeto individual</b> continua sendo
	 * {@code rede.storage.max-object-bytes}, e a conta da cota é por participante, não por bucket —
	 * um participante com dez buckets tem dez vezes o espaço, ou nenhum, e dizer o contrário seria
	 * uma segunda regra de espaço para quem opera descobrir.</p>
	 */
	void exigirCotaDoParticipante(String actorId) {
		exigirCotaDoParticipante(actorId, 0, reservadoEmPartes(actorId));
	}

	/** O reservado em partes abertas desta conta — zero quando a instalação não tem multipart. */
	private long reservadoEmPartes(String actorId) {
		if (jdbc == null || maxBytesPerOwner <= 0 || actorId == null || actorId.isBlank()) {
			return 0;
		}
		Long valor = jdbc.queryForObject("""
			select coalesce(sum(p.bytes), 0) from network_storage_multipart_parts p
			join network_storage_multipart_uploads u on u.upload_id = p.upload_id
			where u.owner_user_id = ?
			""", Long.class, actorId);
		return valor == null ? 0 : Math.max(0, valor);
	}

	/**
	 * A cota de uma conta, com o que ela ocupa e o que ela reserva.
	 *
	 * <p><b>Antes</b> a abertura ({@code beginUpload}) só perguntava {@code usado >= limite}: o espaço
	 * reservado em partes de multipart aberto era invisível ali, então quem tinha a cota inteira em
	 * partes ainda abria um upload e só descobria na conclusão — depois de o corpo ter percorrido a
	 * rede e sido montado em disco. O {@code >=} também deixava passar a abertura de uma conta
	 * exatamente no limite, que só podia terminar em {@code 507} de qualquer forma.</p>
	 *
	 * <p><b>Agora</b> a mesma conta ({@code usado + reservado + esta parte}) vale na abertura e no
	 * envio da parte, e a abertura recusa já quando o reservado sozinho não deixa espaço. A mensagem
	 * carrega os dois números quando há reservado — o participante precisa saber que o espaço que
	 * falta está num upload aberto, e não em objeto gravado.</p>
	 */
	void exigirCotaDoParticipante(String actorId, long acrescimo, long reservado) {
		if (maxBytesPerOwner <= 0 || actorId == null || actorId.isBlank()) {
			return;
		}
		long usado = usadoPorDono.computeIfAbsent(actorId, catalog::bytesOwnedBy);
		// `>=` no que não cresce: uma conta já cheia — ou com o espaço todo reservado em partes —
		// não abre upload que só pode terminar em 507. Com uma gravação em curso, a conta é `>`:
		// cabe exatamente o que sobra.
		boolean cheio = acrescimo <= 0 ? usado + Math.max(0, reservado) >= maxBytesPerOwner
			: usado + Math.max(0, reservado) + acrescimo > maxBytesPerOwner;
		if (cheio) {
			String emPartes = reservado > 0
				? ", tem " + reservado + " em partes abertas" : "";
			throw new ApiException(507, "STORAGE_OWNER_QUOTA_EXCEEDED",
				"O participante ja ocupa " + usado + " bytes" + emPartes + (acrescimo > 0
					? ", esta gravacao soma " + acrescimo : "")
					+ " e a cota desta instalacao e " + maxBytesPerOwner + " bytes.");
		}
	}

	/**
	 * Quanto o participante ocupa e quanto pode ocupar — a leitura que faltava.
	 *
	 * <p>Até o ciclo 80 a cota só existia na <b>recusa</b>: quem recebia {@code 507} ficava sabendo os
	 * números daquele instante, e mais ninguém. O brief §18 pede que o participante "consulte espaço
	 * utilizado" e que a interface informe "quais recursos estão sendo utilizados"; sem esta leitura,
	 * ele só descobria o limite ao bater nele, e não tinha como planejar (apagar antes de encher,
	 * pedir mais cota ao operador).</p>
	 *
	 * <p><b>Lido do banco, não do cache.</b> O cache do {@link #usadoPorDono} é otimização do caminho
	 * de gravação e conta só o que esta instância viu; um painel que respondesse por ele mostraria um
	 * número diferente do que a próxima recusa vai usar, conforme a instância que atendeu. A leitura
	 * é uma consulta indexada por {@code created_by}.</p>
	 *
	 * @param limite  a cota em bytes; {@code 0} quando desligada
	 * @param usado   o que hoje conta contra ela ({@code bytesOwnedBy}: correntes, sem lápide)
	 * @param livre   o que ainda cabe; {@code null} quando a cota está desligada, porque "sem limite"
	 *                não é um número — {@code 0} ali diria "cheio"
	 */
	public record Cota(long limite, long usado, Long livre) {
	}

	/** A cota deste participante, para o painel. Ver {@link Cota}. */
	public Cota cotaDe(String actorId) {
		long usado = actorId == null || actorId.isBlank() ? 0L : catalog.bytesOwnedBy(actorId);
		if (maxBytesPerOwner <= 0) {
			return new Cota(0L, usado, null);
		}
		return new Cota(maxBytesPerOwner, usado, Math.max(0L, maxBytesPerOwner - usado));
	}

	/**
	 * A cota com o tamanho da gravação já na conta.
	 *
	 * <p><b>Por que o acréscimo.</b> Conferir só o que já está gravado deixa o participante entrar a
	 * 1 byte da cota e gravar o objeto máximo: o teto vira conselho. O corpo só é medido <b>depois</b>
	 * de atravessar o fluxo, então quem cobra o tamanho é quem já o mediu — o {@code finishUpload} e o
	 * {@code store}, que recusam antes de gravar e deixam o objeto {@code PENDING} (o retry do cliente
	 * continua válido).</p>
	 */
	void exigirCotaDoParticipante(String actorId, long acrescimo) {
		exigirCotaDoParticipante(actorId, acrescimo, reservadoEmPartes(actorId));
	}

	/**
	 * A cota com as partes abertas de um multipart já na conta.
	 *
	 * <p><b>Por que existe.</b> A cota era conferida na abertura do upload e na conclusão, e nunca nas
	 * partes: um participante com cota de 1 MB abria um multipart, mandava 10 000 partes de 5 MiB (o
	 * máximo que o protocolo aceita) e nunca concluía — 50 GiB de disco reservados, com a cota só
	 * olhando quando já era tarde. O {@code abort} existe, mas quem abusa não o chama.</p>
	 *
	 * <p><b>O que conta.</b> O que já está no catálogo ({@code bytesOwnedBy}) mais o que já está
	 * reservado em partes abertas desta conta, menos a parte que este envio substitui. O espaço é
	 * reservado no envio da parte, não na conclusão — é ali que ele passa a ocupar disco.</p>
	 */
	void exigirCotaDasPartes(String actorId, long reservedPartsBytes, long estaParte) {
		// A conta é a mesma da abertura e da conclusão — um ponto só, para as três não divergirem.
		exigirCotaDoParticipante(actorId, Math.max(0, estaParte), Math.max(0, reservedPartsBytes));
	}

	/**
	 * O que o participante passou a ocupar. Chamado só quando o corpo foi de fato gravado e medido —
	 * a cota segue os bytes do catálogo, não os tentados.
	 */
	private void somarUsoDoParticipante(String actorId, long bytes) {
		if (maxBytesPerOwner <= 0 || actorId == null || actorId.isBlank()) {
			return;
		}
		usadoPorDono.merge(actorId, bytes, Long::sum);
	}

	/**
	 * O que o participante deixou de ocupar. Chamado quando o objeto sai da conta do banco — exclusão,
	 * expiração, retenção ou versão substituída.
	 *
	 * <p><b>Antes:</b> o cache só somava. O número inicial vem de {@code bytesOwnedBy} (que exclui
	 * lápide e versão substituída), mas a partir do primeiro upload ele era a única fonte, e nada o
	 * descontava. Quem enchia a cota, apagava tudo e tentava de novo recebia {@code 507} até o
	 * processo reiniciar, com o número do que já não existia.</p>
	 *
	 * <p>A conta nunca fica negativa: um {@code DELETED} marcado duas vezes (corrida com a varredura)
	 * não pode transformar o desconto em crédito para um upload futuro. Negativo é "não sei", e é
	 * melhor esquecer o valor e reler o banco na próxima vez ({@code remove}).</p>
	 */
	/** O ciclo de vida marca lápide por fora deste serviço; ele avisa aqui que a conta mudou. */
	void esquecerUsoDoParticipante(String actorId) {
		if (maxBytesPerOwner <= 0 || actorId == null || actorId.isBlank()) {
			return;
		}
		// Esquecer é mais seguro do que descontar: o que sai da conta são as bytes daquela linha, e
		// quem descontasse pelo tamanho da versão corrente erraria na versão substituída (tamanho
		// diferente, dono diferente). A releitura no próximo upload é uma consulta indexada por
		// `created_by`, e acontece uma vez por participante que acabou de mudar a própria conta.
		usadoPorDono.remove(actorId);
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
		// `ownerUserId` só é gravado se a coluna ainda estiver vazia (o `coalesce` do UPSERT): o
		// primeiro a tocar na linha é o criador do bucket, e as políticas seguintes não o trocam.
		StorageBucketPolicy saved = catalog.saveBucketPolicy(new StorageBucketPolicy(bucket, versioning,
			retainSupersededDays, expireAfterDays, actorId, now, actorId));
		audit.record(actorId, "STORAGE_BUCKET_POLICY", bucket,
			"{\"versioning\":" + versioning + ",\"retainSupersededDays\":" + json(retainSupersededDays)
				+ ",\"expireAfterDays\":" + json(expireAfterDays) + "}");
		return saved;
	}

	/**
	 * O histórico de <b>todas</b> as chaves do bucket — o {@code ListObjectVersions} do gateway S3.
	 *
	 * <p><b>Antes:</b> só existia o histórico de uma chave. O {@code GET /{bucket}?versions} caía no
	 * {@code ListObjects}, que ignora o subrecurso, e o cliente recebia os objetos correntes com
	 * {@code 200} — nem erro, nem versão.</p>
	 */
	public PaginaDeVersoes versionsNoBucket(String bucket, String prefix, String keyMarker, Long versionMarker,
			int maxKeys) {
		validateBucket(bucket);
		return catalog.paginaDeVersoes(bucket, prefix, keyMarker, versionMarker, maxKeys);
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
		if (current.isPresent()) {
			esquecerUsoDoParticipante(current.get().createdBy());
		}
		esquecerUsoDoParticipante(actorId);
		catalog.insertVersion(id, bucket, key, old.contentType(), actorId, now, next);
		// A versão restaurada é um objeto NOVO, e precisa do corpo dela. Antes, a linha era marcada
		// STORED com o digest da antiga sem nada ser gravado: o catálogo dizia que a chave voltou a
		// ter conteúdo e o download respondia 409 sem cópia — a restauração devolvia um objeto que
		// nenhum disco tinha. O corpo vem da versão antiga, pela mesma leitura do download, e é
		// gravado pelo caminho do upload: o digest que fica é o do que foi escrito, não o prometido.
		try (InputStream corpo = openReplicaZeroBody(old.id())) {
			// Sem `requireStorage`: quem restaura é o Control Plane, e a réplica 0 é dele. A conta
			// da cota é do participante, e a restauração não passa por ela — o espaço já era dele
			// na versão antiga e a nova ocupação é a mesma dos bytes.
			boolean gravou = doFinishUpload(id, corpo, actorId, false) != null;
			if (!gravou) {
				throw new ApiException(409, "STORAGE_VERSION_RACE", "A chave mudou durante a restauracao.");
			}
		}
		catch (java.io.IOException fechou) {
			throw new ApiException(409, "STORAGE_VERSION_NOT_SERVABLE",
				"A copia da versao antiga nao pode ser lida para restaurar.");
		}
		catch (ApiException recusado) {
			// A versão nova já está no catálogo como PENDING e sem corpo: sai da chave para não
			// deixar um objeto que ninguém consegue baixar e que barra a próxima restauração.
			catalog.discardPending(id);
			throw recusado;
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
		try {
			// Quem paga a cota é quem criou o objeto (`created_by`), não o nó que guardou os bytes.
			exigirCotaDoParticipante(object.createdBy(), measured.bytes());
		}
		catch (RuntimeException semCota) {
			deleteQuietly(measured.temp());
			throw semCota;
		}
		// O arquivo vai para o destino ANTES do catálogo: registrar "tem cópia" apontando para um
		// arquivo que ainda não existe daria um objeto servível sem bytes.
		Path destination = bodyPath(object.id());
		Path backup = guardarCorpoAnterior(destination);
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
			restaurarBackup(object.id(), backup, destination);
			throw new ApiException(500, "STORAGE_ROOT_UNAVAILABLE", "Nao foi possivel gravar o objeto.");
		}
		if (backup != null) {
			backups.put(object.id(), backup);
		}
		Instant now = clock.instant();
		if (!catalog.markStored(object.id(), measured.bytes(), measured.sha256(), now)) {
			// Corrida: outra requisição confirmou o objeto entre a leitura e o update. A transição é
			// condicional no estado, então o perdedor descobre aqui em vez de sobrescrever o medido.
			// O arquivo recém-escrito sai: quem venceu gravou o dele no mesmo caminho. O backup também
			// sai — nesta corrida ele pode ser o de antes de outra gravação, e devolvê-lo passaria por
			// cima do que venceu.
			deleteQuietly(destination);
			soltarBackup(object.id());
			throw new ApiException(409, "STORAGE_ALREADY_STORED", "O objeto ja tem copia confirmada.");
		}
		catalog.savePlacement(new StoragePlacement(object.id(), 0, node.id(), measured.bytes(), measured.sha256(),
			StoragePlacement.State.OK, now));
		// Quem pagou a cota foi quem criou o objeto (`created_by`), não o nó que guardou os bytes.
		somarUsoDoParticipante(object.createdBy(), measured.bytes());
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
		exigirCedeParaConfirmar(node, now);
		StoragePlacement confirmed = new StoragePlacement(objectId, placement.replica(), node.id(), bytes,
			object.sha256(), StoragePlacement.State.OK, now);
		catalog.savePlacement(confirmed);
		audit.record(node.id(), "STORAGE_REPLICA_CONFIRMED", objectId,
			"{\"replica\":" + placement.replica() + ",\"bytes\":" + bytes + "}");
		return confirmed;
	}

	/**
	 * A confirmação respeita o teto que o nó declarou — a mesma régua da designação, aplicada no
	 * lugar onde o dado é <b>registrado</b> (doc 13 §5.4.1).
	 *
	 * <p>A checagem do runtime do nó protege o <b>disco</b> de quem cede; esta protege a <b>verdade
	 * do catálogo</b>. Sem ela, um nó que teve o cede reduzido depois da designação — ou um runtime
	 * antigo, sem o `disk_make_room` do §5.4.1 — gravava e confirmava a cópia, e o catálogo passava
	 * a afirmar durabilidade num espaço que o participante não autorizou. A colocação em
	 * {@code PENDING_REMOTE} já conta como reservada, então a folga é a que sobra para esta
	 * confirmação: acima de zero, cabe.</p>
	 */
	private void exigirCedeParaConfirmar(NetworkNode node, Instant now) {
		if (placement == null) {
			return;
		}
		Long folga = placement.cedentHeadroomMb(node, now);
		if (folga != null && folga < 0) {
			throw new ApiException(507, "STORAGE_NO_CEDENT_SPACE",
				"O no cedeu " + node.declaredDiskMb() + " MB e esta copia nao cabe no que resta; "
					+ "a copia nao foi confirmada.");
		}
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

	/**
	 * A versão pedida de uma chave, para o {@code GET}/{@code HEAD} com {@code ?versionId=} (§4.68).
	 *
	 * <p>Vale a mesma régua da corrente: estado servível e cópia conferida. Uma versão
	 * <b>substituída</b> é servível de propósito — é o que o histórico existe para guardar. Lápide,
	 * versão vencida e versão inexistente respondem {@code 404} como objeto inexistente.</p>
	 */
	public StorageObject requireVersion(String bucket, String key, long versao, String operacao) {
		long inicio = System.nanoTime();
		try {
			StorageObject object = doRequireVersao(bucket, key, versao);
			conta(operacao, StorageOperationMetrics.Resultado.OK, inicio);
			return object;
		}
		catch (RuntimeException recusado) {
			conta(operacao, resultadoDe(recusado), inicio);
			throw recusado;
		}
	}

	/**
	 * A linha da versão pedida, com a régua de serventia da corrente.
	 *
	 * <p>{@code catalog.versions} devolve o histórico da chave — inclusive linhas de outras contas no
	 * mesmo bucket —; aqui a escolha é pela versão. Não filtra por dono porque não conhece a
	 * credencial: quem confere é o chamador, em
	 * {@code S3GatewayController.objetoPedido}, que recusa com {@code 403 AccessDenied} (§4.68).</p>
	 */
	private StorageObject doRequireVersao(String bucket, String key, long versao) {
		StorageObject object = catalog.versions(bucket, key).stream()
			.filter(o -> o.version() == versao).findFirst()
			.orElseThrow(() -> new ApiException(404, "NoSuchVersion",
				"Esta chave nao tem a versao pedida."));
		if (!object.servable() || object.expired(clock.instant())) {
			throw new ApiException(404, "NoSuchVersion", "Esta chave nao tem a versao pedida.");
		}
		return object;
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
		return list(bucket, prefix, afterKey, limit, false);
	}

	/**
	 * Listagem com a linha a mais que diz se há próxima página.
	 *
	 * <p><b>Antes:</b> o gateway decidia {@code IsTruncated} por {@code found.size() == maxKeys}, que
	 * confunde "página cheia" com "há mais": um bucket com exatamente {@code max-keys} objetos saía
	 * truncado, com token, e o cliente pedia uma página vazia. Agora o serviço pede {@code limit + 1}
	 * linha ao banco — uma consulta indexada a mais de folga, não uma consulta extra — e quem chama
	 * olha se veio a extra.</p>
	 */
	public List<StorageObject> list(String bucket, String prefix, String afterKey, int limit, boolean espiar) {
		long inicio = System.nanoTime();
		try {
			validateBucket(bucket);
			int bounded = Math.max(1, Math.min(limit <= 0 ? MAX_LIST_LIMIT : limit, MAX_LIST_LIMIT));
			List<StorageObject> achados = catalog.list(bucket, prefix, afterKey, espiar ? bounded + 1 : bounded);
			conta("LIST", StorageOperationMetrics.Resultado.OK, inicio);
			return achados;
		}
		catch (RuntimeException recusado) {
			conta("LIST", resultadoDe(recusado), inicio);
			throw recusado;
		}
	}

	/** O teto efetivo de uma listagem: {@code limit <= 0} é o padrão, e nada passa de {@link #MAX_LIST_LIMIT}. */
	private static int limiteDe(int limit) {
		return Math.max(1, Math.min(limit <= 0 ? MAX_LIST_LIMIT : limit, MAX_LIST_LIMIT));
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
			// A lápide sai da conta do banco (`bytesOwnedBy` exclui `DELETED`): o cache tem de
			// esquecer o número dele, senão o participante que apagou continua barrado pela cota.
			esquecerUsoDoParticipante(object.createdBy());
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

	/**
	 * O nome do bucket como o S3 o aceita — e não só "curto e sem maiúscula".
	 *
	 * <p><b>Antes:</b> bastava ter até 63 caracteres e nenhuma maiúscula. {@code com_underline},
	 * {@code com espaço}, {@code traco-no-fim-}, {@code -traco-no-inicio} e {@code dois..pontos} criavam
	 * bucket com {@code 200}; o S3 recusa os cinco. O nome do bucket é o <b>host</b> do virtual-host
	 * ({@code <bucket>.s3...}), e rótulo DNS não tem {@code _}, espaço, ponto consecutivo, hífen nas
	 * pontas — o bucket criado assim só falharia no DNS de quem apontasse um cliente de verdade.</p>
	 *
	 * <p><b>Agora:</b> 3 a 63 caracteres, começando e terminando por letra ou dígito, só minúscula,
	 * dígito, hífen e ponto, sem {@code ..}, sem {@code .-}, e sem a forma de um IPv4 (que o S3 reserva
	 * para o TLS do caminho virtual-host). A mensagem diz qual regra foi violada.</p>
	 */
	private static void validateBucket(String bucket) {
		if (bucket == null || bucket.isBlank()) {
			throw new ApiException(400, "STORAGE_BUCKET_INVALID", "Nome de bucket invalido.");
		}
		if (bucket.length() < 3 || bucket.length() > 63) {
			throw new ApiException(400, "STORAGE_BUCKET_INVALID",
				"Nome de bucket deve ter de 3 a 63 caracteres.");
		}
		if (!bucket.equals(bucket.toLowerCase(java.util.Locale.ROOT))) {
			// S3 proíbe maiúscula em bucket: aceitar aqui daria duas grafias para o mesmo bucket, e a
			// única de `bucket` na tabela passaria a esconder colisão.
			throw new ApiException(400, "STORAGE_BUCKET_INVALID", "O nome do bucket deve ser minusculo.");
		}
		if (!bucket.matches("[a-z0-9.\\-]+")) {
			throw new ApiException(400, "STORAGE_BUCKET_INVALID",
				"O nome do bucket aceita apenas minuscula, digito, hifen e ponto.");
		}
		if (!bucket.matches("[a-z0-9].*[a-z0-9]")) {
			throw new ApiException(400, "STORAGE_BUCKET_INVALID",
				"O nome do bucket deve comecar e terminar com letra ou digito.");
		}
		if (bucket.contains("..") || bucket.contains(".-")) {
			throw new ApiException(400, "STORAGE_BUCKET_INVALID",
				"O nome do bucket nao aceita ponto consecutivo nem ponto antes de hifen.");
		}
		if (IPV4.matcher(bucket).matches()) {
			throw new ApiException(400, "STORAGE_BUCKET_INVALID",
				"O nome do bucket nao pode ter a forma de um endereco IPv4.");
		}
	}

	/** A forma de um IPv4, que o S3 reserva: um bucket assim colidiria com um host de TLS. */
	private static final java.util.regex.Pattern IPV4 =
		java.util.regex.Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

	/**
	 * Grava o corpo num temporário e o devolve medido. O leitor não guarda o objeto inteiro em
	 * memória: 5 GB passariam em pedaços de 64 KiB. O temporário só vira o arquivo do objeto se
	 * tudo der certo — upload interrompido deixa lixo apagável, nunca um objeto pela metade.
	 */
	/**
	 * Lê o corpo para um temporário, medindo o digest e o tamanho — <b>parando no teto</b>.
	 *
	 * <p><b>Antes:</b> o corpo inteiro era copiado para o disco e o teto só era conferido depois, em
	 * {@code doFinishUpload}. Um cliente que mandasse 50 GiB num limite de 5 GiB fazia o servidor
	 * <b>escrever 50 GiB em {@code /tmp}</b> para então responder {@code 413}. O custo não é só o
	 * espaço: é o upload longo que já ia ser recusado ocupando o disco de quem está gravando de
	 * verdade.</p>
	 *
	 * <p><b>Agora:</b> a conta é feita ao longo da leitura, e a primeira leitura que passar do teto
	 * interrompe — o temporário é apagado e a resposta é {@code 413 STORAGE_OBJECT_TOO_LARGE}, antes
	 * de qualquer byte virar corpo. O limite é lido no começo do corpo, mais o tamanho de um bloco:
	 * ler "só o teto" e parar deixaria passar um corpo de exatamente {@code teto + 1} como se fosse
	 * o teto.</p>
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
			while ((read = lerNoMaximo(input, buffer, maxObjectBytes - total + 1)) != -1) {
				total += read;
				if (total > maxObjectBytes) {
					// O corpo passou do teto: nada dele será guardado, e continuar lendo só gastaria
					// disco para a mesma recusa.
					deleteQuietly(temp);
					throw new ApiException(413, "STORAGE_OBJECT_TOO_LARGE",
						"O objeto excede o teto desta instalacao (" + maxObjectBytes + " bytes).");
				}
				digest.update(buffer, 0, read);
				sink.write(buffer, 0, read);
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
	/**
	 * Lê no máximo {@code limite} bytes — um byte além do teto basta para saber que ele passou.
	 *
	 * <p>Sem isto, um bloco de 64 KiB inteiro era lido (e descartado) mesmo quando só faltavam dois
	 * bytes para o teto. Não é o custo do disco — nada dele era escrito —, é o do corpo: o servidor
	 * pede 64 KiB a mais ao cliente para recusar.</p>
	 */
	private static int lerNoMaximo(InputStream input, byte[] buffer, long limite) throws IOException {
		int pedido = (int) Math.min(buffer.length, Math.max(1, limite));
		return input.read(buffer, 0, pedido);
	}

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
			// A réplica 0 tem linha de colocação (nó `control-plane` no upload, ou o nó dono do disco
			// deste runtime). Ela passa pela MESMA régua da cópia de nó: `STALE`/`GONE` no catálogo
			// não é servida a quem puxa o objeto para replicar. Antes, bastava o arquivo ser legível
			// na raiz: o nó que relatou o próprio disco divergente a marcava `STALE`, e esta rota
			// — a origem de toda replicação — continuava entregando o corpo que o catálogo negava.
			exigirReplicaZeroServivel(objectId);
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
	/**
	 * A réplica 0 só sai da raiz do Control Plane quando o catálogo não a marcou como divergente.
	 *
	 * <p>Sem linha de colocação a leitura continua valendo: objeto gravado antes de a colocação do
	 * Control Plane existir nunca foi declarado divergente por ninguém. Com linha, só {@code OK}
	 * serve — o mesmo que a cópia de nó. A réplica 0 não vira {@code GONE} no healing (§5.13, o
	 * corpo certo é reescrito por quem grava), então {@code STALE} é o único sinal que ela tem, e é
	 * ele que esta guarda cobra.</p>
	 */
	private void exigirReplicaZeroServivel(String objectId) {
		// O dono da linha é `control-plane` quando quem gravou foi o CP, e o id do nó quando quem
		// gravou foi um runtime. Quem responde é a linha, não o nome: perguntar por um dono fixo
		// deixaria de fora justamente a réplica 0 de um nó.
		catalog.copies(objectId).stream()
			.filter(copia -> copia.replica() == 0)
			.filter(copia -> copia.state() != StoragePlacement.State.OK)
			.findFirst()
			.ifPresent(copia -> {
				throw new ApiException(409, "STORAGE_REPLICA_STALE",
					"A copia na replica 0 nao confere (" + copia.state().name() + "); use uma copia OK.");
			});
	}

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