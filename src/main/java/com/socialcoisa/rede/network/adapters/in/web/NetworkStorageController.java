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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.socialcoisa.rede.network.application.NodeKeyService;
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
	private final NodeKeyService keys;

	NetworkStorageController(StorageCatalogService storage, NodeKeyService keys) {
		this.storage = storage;
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

	/** O que este nó hospeda: identidade lógica e medidas, nunca o conteúdo. */
	@GetMapping("/placements")
	List<PlacementView> placements(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		requireStorage(node);
		return storage.placementsOn(node.id()).stream().map(PlacementView::of).toList();
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

	record PlacementView(String objectId, int replica, String nodeId, long bytes, String sha256, String state) {
		static PlacementView of(StoragePlacement placement) {
			return new PlacementView(placement.objectId(), placement.replica(), placement.nodeId(),
				placement.bytes(), placement.sha256(), placement.state().name());
		}
	}
}