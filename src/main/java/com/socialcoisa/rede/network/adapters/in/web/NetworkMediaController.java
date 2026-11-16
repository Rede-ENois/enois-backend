package com.socialcoisa.rede.network.adapters.in.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.network.application.MediaTranscodeService;
import com.socialcoisa.rede.network.application.NodeKeyService;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Transcode delegado a nó {@code NODE_MEDIA} (fase 3): o nó pega o objeto e devolve o resultado.
 *
 * <p>Duas rotas e nada mais. O nó não escolhe mídia: ele só lê e escreve a que a tarefa designada a
 * ele aponta, e a permissão é conferida nas duas — perfil {@code NODE_MEDIA} <em>e</em> designação
 * vigente. Sem a designação, um nó com a chave certa leria e escreveria qualquer objeto.</p>
 *
 * <p>O resultado entra num ponto de staging do bucket, que o proxy público não serve: conferir o
 * arquivo não o publica. Toda a escrita aqui é bytes crus no corpo da requisição — nada é
 * interpretado, nada é executado.</p>
 */
@RestController
@RequestMapping("/network/media")
class NetworkMediaController {

	private final MediaTranscodeService media;
	private final NodeKeyService keys;

	NetworkMediaController(MediaTranscodeService media, NodeKeyService keys) {
		this.media = media;
		this.keys = keys;
	}

	/** O objeto da tarefa, para o nó que a tem designada. Suporta {@code Range} como o proxy. */
	@GetMapping("/{mediaId}/source")
	ResponseEntity<StreamingResponseBody> source(
			@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey, @PathVariable String mediaId,
			@RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		MediaObjectStore.StoredObject object = media.openSource(node, mediaId, range);
		ResponseEntity.BodyBuilder response = ResponseEntity
			.status(object.contentRange() == null ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT)
			.header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
			.header(HttpHeaders.CONTENT_DISPOSITION, "attachment")
			.header(HttpHeaders.ACCEPT_RANGES, "bytes")
			// Objeto de trabalho de um nó: não fica em cache compartilhado.
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
			.contentLength(object.length());
		if (object.contentRange() != null) {
			response.header(HttpHeaders.CONTENT_RANGE, object.contentRange());
		}
		if (object.eTag() != null) {
			response.eTag(object.eTag());
		}
		return response.body(output -> copy(object, output));
	}

	/**
	 * O arquivo transcodificado. O Control Plane mede tamanho e sha-256 enquanto grava — o que fica
	 * guardado é o medido, não o declarado. A resposta devolve as duas medidas para o nó poder
	 * declarar exatamente isso em {@code POST /network/tasks/{id}/result}.
	 */
	@PutMapping("/{mediaId}/result")
	Map<String, Object> result(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey, @PathVariable String mediaId,
			@RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
			HttpServletRequest request) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		String mime = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
		MediaTranscodeService.StoredResult stored = media.storeResult(node, mediaId, mime, requestBody(request));
		return Map.of("status", "STORED", "resultKey", stored.resultKey(), "resultBytes", stored.bytes(),
			"resultSha256", stored.sha256());
	}

	private static InputStream requestBody(HttpServletRequest request) {
		try {
			return request.getInputStream();
		}
		catch (IOException unreadable) {
			throw new ApiException(400, "MEDIA_UPLOAD_FAILED", "Nao foi possivel ler o arquivo enviado.");
		}
	}

	private static void copy(MediaObjectStore.StoredObject object, OutputStream output) throws IOException {
		try (InputStream body = object.body()) {
			body.transferTo(output);
		}
	}
}