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
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.network.domain.TaskType;
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

	StorageCatalogService(StorageCatalogRepository catalog, AuditTrail audit, Clock clock,
			@Value("${rede.storage.max-object-bytes:5368709120}") long maxObjectBytes,
			@Value("${rede.storage.root:${java.io.tmpdir}/enois-storage}") String storageRoot,
			StoragePlacementService placement, @Nullable NodeBodyStore nodeBodies) {
		this.catalog = catalog;
		this.audit = audit;
		this.clock = clock;
		this.maxObjectBytes = maxObjectBytes;
		this.storageRoot = storageRoot;
		this.placement = placement;
		this.nodeBodies = nodeBodies;
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
		StorageObject object = catalog.createPending(OBJECT_ID_PREFIX + NodeIds.next(""), bucket, key, type,
			actorId, now);
		if (object.state() == StorageObjectState.DELETED) {
			// A chave é um objeto novo depois da lápide: reabrir tem de ser explícito, senão o cliente
			// reenvia o arquivo e nada aparece no download — falha silenciosa do pior tipo.
			throw new ApiException(409, "STORAGE_KEY_DELETED",
				"Esta chave foi removida e ainda espera coleta no no; remova a lapide ou use outra chave.");
		}
		return object;
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
		if (node.observedDiskFreeMb() != null && node.observedDiskFreeMb() < MIN_DISK_FREE_MB) {
			throw new ApiException(507, "STORAGE_NO_DISK_SPACE",
				"O no informa " + node.observedDiskFreeMb() + " MB livres, abaixo do minimo de "
					+ MIN_DISK_FREE_MB + " MB para receber objeto.");
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
			Files.move(measured.temp(), destination, StandardCopyOption.REPLACE_EXISTING);
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

	/** O objeto só é servível com cópia conferida; {@code PENDING} responde 404 como se não existisse. */
	public StorageObject requireDownloadable(String bucket, String key) {
		StorageObject object = catalog.find(bucket, key)
			.orElseThrow(() -> new ApiException(404, "STORAGE_NOT_FOUND", "Objeto nao encontrado."));
		if (!object.servable()) {
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
		validateBucket(bucket);
		int bounded = Math.max(1, Math.min(limit <= 0 ? MAX_LIST_LIMIT : limit, MAX_LIST_LIMIT));
		return catalog.list(bucket, prefix, afterKey, bounded);
	}

	/** Lápide: idempotente, auditada, e não apaga a linha — as cópias nos nós ainda precisam ser coletadas. */
	@Transactional
	public boolean delete(String actorId, String bucket, String key) {
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
		if (Files.isReadable(cpBody)) {
			return openVerified(cpBody, object);
		}
		if (nodeBodies != null && nodeBodies.has(objectId)) {
			return openVerified(nodeBodies.pathOf(objectId), object);
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
			if (expected != null && expected != size) {
				throw new ApiException(410, "STORAGE_BODY_SIZE_MISMATCH",
					"O corpo tem " + size + " bytes, mas o catalogo registra " + expected + ".");
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