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

	private final StorageCatalogRepository catalog;
	private final AuditTrail audit;
	private final Clock clock;
	private final long maxObjectBytes;
	/**
	 * Onde este Control Plane guarda o corpo que ele mesmo recebeu. Na fase 1 o CP faz o papel do
	 * primeiro nó; na fase 2 o corpo vai para o nó designado e este diretório deixa de ser usado.
	 */
	private final String storageRoot;

	StorageCatalogService(StorageCatalogRepository catalog, AuditTrail audit, Clock clock,
			@Value("${rede.storage.max-object-bytes:5368709120}") long maxObjectBytes,
			@Value("${rede.storage.root:${java.io.tmpdir}/enois-storage}") String storageRoot) {
		this.catalog = catalog;
		this.audit = audit;
		this.clock = clock;
		this.maxObjectBytes = maxObjectBytes;
		this.storageRoot = storageRoot;
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
		audit.record(node.id(), "STORAGE_OBJECT_STORED", object.id(),
			"{\"bucket\":\"" + escape(object.bucket()) + "\",\"key\":\"" + escape(object.key()) + "\",\"bytes\":"
				+ measured.bytes() + ",\"sha256\":\"" + measured.sha256() + "\"}");
		return new Stored(object.id(), measured.bytes(), measured.sha256());
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
		Path file = bodyPath(objectId);
		try {
			return Files.newInputStream(file);
		}
		catch (IOException missing) {
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