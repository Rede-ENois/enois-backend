package com.socialcoisa.rede.media.adapters.in.web;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
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

import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * A mídia passa pelo nosso host, em vez de o navegador falar com o bucket.
 *
 * <p>O gateway S3 do b7cloud não implementa CORS (`PutBucketCors` responde `InternalError`) e o
 * bucket não é público: o `PUT` assinado direto no bucket morre no preflight e nenhuma imagem
 * abre. Servindo `/api/storage/{chave}` daqui, o navegador fica na mesma origem da API e o pod
 * fala com o bucket usando a credencial que já tem.</p>
 *
 * <p>O caminho é o mesmo `objectKey` gravado no asset, então as URLs antigas só precisam de troca
 * de prefixo — nada de remapear objeto.</p>
 */
@RestController
@RequestMapping("/storage")
class MediaStorageProxyController {

	/** Vazio quando o storage e o ImageKit (nao ha bucket para servir). */
	private final ObjectProvider<MediaObjectStore> storeProvider;
	private final MediaAssetRepository assets;
	private final CurrentUserResolver currentUserResolver;
	private final com.socialcoisa.rede.media.application.MediaMetadataSanitizer sanitizer;

	MediaStorageProxyController(ObjectProvider<MediaObjectStore> storeProvider, MediaAssetRepository assets,
			CurrentUserResolver currentUserResolver,
			com.socialcoisa.rede.media.application.MediaMetadataSanitizer sanitizer) {
		this.storeProvider = storeProvider;
		this.assets = assets;
		this.currentUserResolver = currentUserResolver;
		this.sanitizer = sanitizer;
	}

	private MediaObjectStore store() {
		MediaObjectStore store = storeProvider.getIfAvailable();
		if (store == null) {
			throw new ApiException(503, "MEDIA_STORAGE_DISABLED", "Armazenamento de midia indisponivel.");
		}
		return store;
	}

	/**
	 * Leitura publica, como o `GET /media/{id}`: a chave so aparece em conteudo que qualquer
	 * pessoa ja pode ver, e `<img src>` nao manda `Origin` — exigir sessao aqui deixaria o post
	 * sem imagem para quem nao entrou.
	 *
	 * <p>Sem migracao oportunista: a leitura e streaming direto do storage ativo. Objetos antigos
	 * gravados no ImageKit continuam servidos pelas URLs dele gravadas no banco; objetos novos
	 * nascem no S3 ja limpos (a sanitizacao acontece na escrita). Nada de escrita dentro de
	 * leitura — era isso que causava hangs de 30-60s no primeiro acesso (25/10).</p>
	 */
	@GetMapping("/{*key}")
	ResponseEntity<StreamingResponseBody> read(@PathVariable String key,
			@RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
		String objectKey = objectKey(key);
		MediaObjectStore.StoredObject object;
		try {
			object = store().read(objectKey, range);
		}
		catch (RuntimeException missing) {
			throw new ApiException(404, "MEDIA_NOT_FOUND", "Midia nao encontrada.");
		}
		MediaAsset asset = assets.findById(mediaIdOf(objectKey)).orElse(null);
		String mime = asset != null ? asset.mimeType() : object.contentType();
		ResponseEntity.BodyBuilder response = ResponseEntity
			.status(object.contentRange() == null ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT)
			.header(HttpHeaders.CONTENT_TYPE, mime == null ? "application/octet-stream" : mime)
			.header(HttpHeaders.ACCEPT_RANGES, "bytes")
			// A chave tem uuid: o conteúdo daquele endereço nunca muda, então pode ficar em cache.
			.header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable");
		if (object.contentRange() != null) {
			response.header(HttpHeaders.CONTENT_RANGE, object.contentRange());
		}
		if (object.eTag() != null) {
			response.eTag(object.eTag());
		}
		return response.body(output -> {
			try (var body = object.body()) {
				body.transferTo(output);
			}
		});
	}

	/**
	 * Subida pelo app (o navegador nao consegue PUT no bucket). A autorizacao e a sessao do dono
	 * do asset, e a chave precisa bater exatamente com a que foi registrada para ele — assim esta
	 * rota nao vira um "escreva em qualquer lugar do bucket".
	 */
	@PutMapping("/{*key}")
	ResponseEntity<Map<String, String>> write(@PathVariable String key,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
			HttpServletRequest request) {
		CurrentUser user = currentUserResolver.resolve(null, sessionToken);
		String objectKey = objectKey(key);
		MediaAsset asset = assets.findById(mediaIdOf(objectKey))
			.orElseThrow(() -> new ApiException(404, "MEDIA_NOT_FOUND", "Midia nao encontrada."));
		if (!asset.ownerId().value().equals(user.id().value())) {
			throw new ApiException(403, "MEDIA_FORBIDDEN", "Esta midia nao e sua.");
		}
		if (!asset.objectKey().equals(objectKey)) {
			throw new ApiException(400, "MEDIA_KEY_MISMATCH", "Chave de midia invalida.");
		}
		long length = request.getContentLengthLong();
		if (length <= 0) {
			throw new ApiException(411, "MEDIA_LENGTH_REQUIRED", "Informe o tamanho do arquivo.");
		}
		String mime = asset.mimeType();
		try {
			if (length > com.socialcoisa.rede.media.application.MediaMetadataSanitizer.MAX_IN_MEMORY) {
				if (sanitizer.supportsInMemory(mime)) {
					throw new ApiException(413, "MEDIA_TOO_LARGE",
						"Arquivo grande demais para a limpeza de metadados.");
				}
				// Vídeo/áudio grande: arquivo temporário + ffmpeg, sem carregar tudo em memória.
				java.nio.file.Path sanitized = sanitizer.sanitizeToFile(request.getInputStream(), mime,
					asset.fileName());
				try {
					try (var body = java.nio.file.Files.newInputStream(sanitized)) {
						store().write(objectKey, mime, java.nio.file.Files.size(sanitized), body);
					}
				}
				finally {
					java.nio.file.Files.deleteIfExists(sanitized);
				}
			}
			else {
				byte[] raw = request.getInputStream().readAllBytes();
				// LIMPEZA NA ESCRITA: EXIF/GPS/XMP/IPTC não chegam ao bucket.
				byte[] clean = sanitizer.sanitize(raw, mime, asset.fileName());
				store().write(objectKey, mime, clean.length, new java.io.ByteArrayInputStream(clean));
			}
		}
		catch (java.io.IOException failure) {
			throw new ApiException(400, "MEDIA_UPLOAD_FAILED", "Nao foi possivel ler o arquivo enviado.");
		}
		return ResponseEntity.ok(Map.of("status", "STORED", "objectKey", objectKey));
	}

	/** O `{*key}` chega com a barra inicial; devolvemos o caminho relativo ao bucket. */
	private static String objectKey(String key) {
		String objectKey = key == null ? "" : key.replaceAll("^/+", "");
		if (objectKey.isBlank() || objectKey.contains("..")) {
			throw new ApiException(400, "MEDIA_INVALID_KEY", "Chave de midia invalida.");
		}
		return objectKey;
	}

	/** `uploads/media_<id>/media/<uuid>/arquivo` — o prefixo do bucket pode ou não ter `uploads/`. */
	private static String mediaIdOf(String objectKey) {
		for (String part : objectKey.split("/")) {
			if (part.startsWith("media_")) {
				return part;
			}
		}
		throw new ApiException(400, "MEDIA_INVALID_KEY", "Chave de midia invalida.");
	}
}
