package com.socialcoisa.rede.network.adapters.in.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.socialcoisa.rede.network.application.NodeKeyService;
import com.socialcoisa.rede.network.application.NodeStorageInventory;
import com.socialcoisa.rede.network.application.StorageCatalogService;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O lado do nó do Énóis Storage (fase 1): gravar e ler o corpo de um objeto do catálogo.
 *
 * <p>Três rotas, todas com a mesma credencial que o nó já usa em todo o resto da rede
 * ({@code X-Node-Id} + {@code X-Node-Key}) — nenhuma identidade nova, nenhum protocolo paralelo
 * (brief §2). O nó não escolhe o que grava: ele grava o que o catálogo aponta e só se tiver o
 * perfil {@code NODE_STORAGE}.</p>
 *
 * <p><b>O corpo é bytes crus, nunca interpretado.</b> O Control Plane mede tamanho e SHA-256
 * enquanto atravessa o fluxo e é o que fica no catálogo. O download devolve o que o catálogo diz
 * que está lá, com {@code Content-Length} e {@code ETag} — sem renderizar, sem servir HTML.</p>
 */
@RestController
@RequestMapping("/network/storage")
class NetworkStorageController {

	private final StorageCatalogService storage;
	private final NodeStorageInventory inventory;
	private final NodeKeyService keys;

	NetworkStorageController(StorageCatalogService storage, NodeStorageInventory inventory, NodeKeyService keys) {
		this.storage = storage;
		this.inventory = inventory;
		this.keys = keys;
	}

	/**
	 * Grava o corpo do objeto. A resposta traz as medidas que o nó pode declarar no resultado da
	 * tarefa — e que ele não pode inventar: quem mede é este lado.
	 */
	@PutMapping("/objects/{objectId}")
	Map<String, Object> put(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey, @PathVariable String objectId,
			HttpServletRequest request) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		StorageCatalogService.Stored stored = storage.store(node, objectId, requestBody(request));
		return Map.of("objectId", stored.objectId(), "bytes", stored.bytes(), "sha256", stored.sha256(),
			"status", "STORED");
	}

	/**
	 * Devolve o corpo do objeto ao nó que o hospeda ou que a tarefa designou. Nesta fase a leitura é
	 * aberta a qualquer nó com o perfil de storage; a designação por tarefa entra junto com o
	 * {@code OBJECT_GET} na fase 2, quando houver mais de uma cópia para escolher.
	 */
	@GetMapping("/objects/{objectId}")
	ResponseEntity<StreamingResponseBody> get(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey, @PathVariable String objectId) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		requireStorage(node);
		StorageObject object = storage.byId(objectId)
			.orElseThrow(() -> new ApiException(404, "STORAGE_NOT_FOUND", "Objeto nao encontrado."));
		if (!object.servable()) {
			throw new ApiException(404, "STORAGE_NOT_FOUND", "Objeto nao encontrado.");
		}
		// A cópia tem de existir em ALGUM nó: catálogo dizendo "tem cópia" sem colocação é metadado
		// divergente, e servir isso mentiria sobre onde o arquivo está.
		List<StoragePlacement> location = storage.location(object.id());
		if (location.isEmpty()) {
			throw new ApiException(409, "STORAGE_PLACEMENT_MISSING",
				"O catalogo diz que o objeto existe, mas nao ha copia registrada em no nenhum.");
		}
		ResponseEntity.BodyBuilder response = ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_TYPE, object.contentType())
			.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(object.bytes()))
			// Objeto de storage: conteúdo do cliente, não fica em cache compartilhado do caminho.
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
			.eTag("\"" + object.sha256() + "\"");
		return response.body(output -> writeBody(storage.readBody(object.id()), output));
	}

	/**
	 * O corpo por <b>digest</b>: é assim que uma réplica referencia a cópia que tem sem depender do
	 * id lógico do catálogo, que vive no Control Plane e não no nó. O digest é o sha-256 que o
	 * Control Plane mediu; quem responde busca o objeto com esse digest em <b>sua própria</b>
	 * colocação e só serve se a cópia estiver {@code OK} — cópia designada e não confirmada não é
	 * cópia.
	 *
	 * <p>O nó que quer a cópia chama esta rota na URL do Control Plane com a própria chave; ele
	 * nunca recebe IP nem hostname de outro storage node (brief §17).</p>
	 */
	@GetMapping("/objects/by-digest/{sha256}")
	ResponseEntity<StreamingResponseBody> getByDigest(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey, @PathVariable String sha256) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		requireStorage(node);
		// Só hex de 64: um digest com outra forma não é um digest, e varrer o catálogo com ele
		// seria trabalho sem destino.
		if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
			throw new ApiException(400, "STORAGE_DIGEST_INVALID", "Digest invalido.");
		}
		StorageObject object = storage.byDigest(sha256)
			.orElseThrow(() -> new ApiException(404, "STORAGE_NOT_FOUND", "Objeto nao encontrado."));
		boolean holds = storage.location(object.id()).stream()
			.anyMatch(placement -> placement.state() == StoragePlacement.State.OK
				&& placement.nodeId().equals(node.id()));
		if (!holds) {
			throw new ApiException(403, "STORAGE_NOT_HELD",
				"Este no nao tem copia conferida deste objeto.");
		}
		ResponseEntity.BodyBuilder response = ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_TYPE, object.contentType())
			.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(object.bytes()))
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
			.eTag("\"" + object.sha256() + "\"");
		return response.body(output -> writeBody(storage.readBody(object.id()), output));
	}

	/**
	 * A réplica confirma a cópia designada: o que ela mediu ao gravar. Os bytes e o sha-256 são
	 * confrontados com o medido no Control Plane — cópia que não confere não conta como durabilidade.
	 */
	@PostMapping("/objects/{objectId}/replica")
	Map<String, Object> confirmReplica(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey, @PathVariable String objectId,
			@RequestBody(required = false) ReplicaConfirmation request) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		if (request == null) {
			throw new ApiException(400, "STORAGE_REPLICA_BODY_MISSING", "Corpo da confirmacao ausente.");
		}
		StoragePlacement confirmed = storage.confirmReplica(node, objectId, request.bytes(), request.sha256());
		return Map.of("objectId", confirmed.objectId(), "replica", confirmed.replica(),
			"state", confirmed.state().name());
	}

	/** O que este nó hospeda: identidade lógica e medidas, nunca o conteúdo. */
	@GetMapping("/placements")
	List<PlacementView> placements(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		requireStorage(node);
		return storage.placementsOn(node.id()).stream().map(PlacementView::of).toList();
	}

	/**
	 * O que este nó <b>de fato</b> tem no disco, comparado com o que o catálogo diz que ele deve ter.
	 *
	 * <p>Resolve três lacunas de uma vez: dá ao operador o que pode ser podado (órfão), diz o que
	 * foi colocado e sumiu ({@code missing}) e o que está lá e não confere ({@code divergent}). É a
	 * única visão do disco de um nó que o Control Plane consegue ter sem abrir conexão para ele.</p>
	 */
	@GetMapping("/inventory")
	NodeStorageInventory.Report inventory(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		requireStorage(node);
		return inventory.inventory(node);
	}

	/**
	 * Apaga do disco deste nó os corpos que o catálogo <b>não atribui mais</b> a ele.
	 *
	 * <p>{@code dryRun} é o padrão: apagar disco de quem contribui é ato de operador, com o número
	 * na mão. O que sai é só órfão — arquivo que nenhuma colocação deste nó reivindica. Cópia
	 * colocada e divergente <b>não</b> se apaga aqui: é a única cópia possivelmente boa do objeto, e
	 * o caminho é re-replicar, não descartar.</p>
	 */
	@PostMapping("/inventory/purge")
	NodeStorageInventory.Purge purgeOrphans(
			@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey,
			@RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		requireStorage(node);
		return inventory.purgeOrphans(node, dryRun);
	}

	/** O que o catálogo sabe deste objeto: as duas medidas e onde estão as cópias. */
	@GetMapping("/objects/{objectId}/location")
	Map<String, Object> location(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey, @PathVariable String objectId) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		requireStorage(node);
		StorageObject object = storage.byId(objectId)
			.orElseThrow(() -> new ApiException(404, "STORAGE_NOT_FOUND", "Objeto nao encontrado."));
		return Map.of("objectId", object.id(), "bucket", object.bucket(), "key", object.key(),
			"bytes", object.bytes() == null ? 0 : object.bytes(), "sha256", object.sha256() == null ? "" : object.sha256(),
			"state", object.state().name(),
			"nodes", storage.location(object.id()).stream().map(StoragePlacement::nodeId).toList());
	}

	/**
	 * A listagem do bucket, para o nó saber o que ainda deve ter e para a fase 3 varrer órfãos.
	 * Paginação por chave: {@code after} é a última chave da página anterior.
	 */
	@GetMapping("/list")
	Map<String, Object> list(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey, @RequestParam String bucket,
			@RequestParam(required = false) String prefix, @RequestParam(required = false) String after,
			@RequestParam(defaultValue = "0") int limit) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		requireStorage(node);
		List<StorageObject> objects = storage.list(bucket, prefix, after, limit);
		return Map.of("bucket", bucket, "objects", objects.stream().map(o -> Map.of("id", o.id(), "key", o.key(),
			"bytes", o.bytes() == null ? 0 : o.bytes(), "state", o.state().name())).toList(),
			"nextAfter", objects.isEmpty() ? "" : objects.get(objects.size() - 1).key());
	}

	/** Disco não dá permissão: sem o perfil, um nó com a chave certa não lê nem escreve objeto. */
	private static void requireStorage(NetworkNode node) {
		if (!com.socialcoisa.rede.network.domain.TaskType.OBJECT_GET.allowedFor(node.profiles())) {
			throw new ApiException(403, "NODE_PROFILE_REQUIRED", "O no nao tem o perfil de armazenamento.");
		}
	}

	private static InputStream requestBody(HttpServletRequest request) {
		try {
			return request.getInputStream();
		}
		catch (IOException unreadable) {
			throw new ApiException(400, "STORAGE_UPLOAD_FAILED", "Nao foi possivel ler o corpo enviado.");
		}
	}

	private static void writeBody(InputStream body, OutputStream output) throws IOException {
		try (InputStream input = body) {
			input.transferTo(output);
		}
	}

	/** Confirmação de cópia: o medido pela réplica, não o que o Control Plane esperava. */
	record ReplicaConfirmation(long bytes, String sha256) {
	}

	record PlacementView(String objectId, int replica, String nodeId, long bytes, String sha256, String state) {
		static PlacementView of(StoragePlacement placement) {
			return new PlacementView(placement.objectId(), placement.replica(), placement.nodeId(),
				placement.bytes(), placement.sha256(), placement.state().name());
		}
	}
}