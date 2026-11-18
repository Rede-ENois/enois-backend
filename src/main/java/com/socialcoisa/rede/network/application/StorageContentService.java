package com.socialcoisa.rede.network.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.socialcoisa.rede.network.domain.ByteRange;
import com.socialcoisa.rede.network.domain.CachePolicy;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A resposta HTTP de um objeto do catálogo, com {@code ETag}, {@code Last-Modified}, {@code
 * Cache-Control}, {@code 304} condicional e {@code Range}/206/416.
 *
 * <p><b>Por que existe:</b> a entrega pública (§13, `GET /network/storage/objects/{id}/content`) e o
 * <b>gateway S3</b> (§11) têm de responder o mesmo conteúdo com os mesmos cabeçalhos. Duas cópias
 * dessa lógica divergem — e a que divergisse serviria o corpo inteiro a um cliente que pediu uma
 * faixa, ou devolveria 200 a um {@code If-None-Match} que deveria dar 304. É uma regra, um serviço.</p>
 *
 * <p><b>O que cada um decide e este serviço não:</b> autorização (o gateway confere a credencial S3
 * antes, a rota pública não exige) e o <i>nome</i> das coisas. O cabeçalho {@code ETag} é sempre o
 * digest entre aspas — o objeto do catálogo é imutável, então o sha-256 É a identidade do conteúdo.</p>
 */
@Service
public class StorageContentService {

	private final StorageCatalogService storage;

	StorageContentService(StorageCatalogService storage) {
		this.storage = storage;
	}

	/** O objeto servível e não vencido, pelo id. 404 com o mesmo código das rotas de leitura. */
	public StorageObject servable(String objectId) {
		StorageObject object = storage.byId(objectId)
			.orElseThrow(() -> new ApiException(404, "STORAGE_NOT_FOUND", "Objeto nao encontrado."));
		// Vencido responde 404 na leitura, não só quando a varredura passar (brief §14).
		if (!object.servable() || object.expired(Instant.now())) {
			throw new ApiException(404, "STORAGE_NOT_FOUND", "Objeto nao encontrado.");
		}
		return object;
	}

	/** O {@code ETag} canônico do objeto — o que o cliente guarda e o que o {@code 304} devolve. */
	public static String etag(StorageObject object) {
		return "\"" + object.sha256() + "\"";
	}

	/**
	 * Resposta completa: {@code 304} se {@code ifNoneMatch} bater, {@code 206}/{@code 416} pelo
	 * {@code Range}, senão {@code 200}.
	 */
	public ResponseEntity<StreamingResponseBody> respond(StorageObject object, String rangeHeader,
			String ifNoneMatch) {
		List<StoragePlacement> location = storage.location(object.id());
		if (location.isEmpty()) {
			throw new ApiException(409, "STORAGE_PLACEMENT_MISSING",
				"O catalogo diz que o objeto existe, mas nao ha copia registrada em no nenhum.");
		}
		long total = object.bytes();
		CachePolicy policy = CachePolicy.forContentType(object.contentType());
		String etag = etag(object);

		// Pedido condicional: o cliente que já tem o conteúdo não paga o corpo de novo.
		if (matches(etag, ifNoneMatch)) {
			ResponseEntity.BodyBuilder notModified = ResponseEntity.status(HttpStatus.NOT_MODIFIED)
				.header(HttpHeaders.ETAG, etag)
				.header(HttpHeaders.CACHE_CONTROL, policy.headerValue());
			if (object.updatedAt() != null) {
				notModified.lastModified(object.updatedAt().toEpochMilli());
			}
			return notModified.build();
		}

		ResponseEntity.BodyBuilder response;
		Optional<ByteRange> range;
		try {
			range = ByteRange.parse(rangeHeader, total);
		}
		catch (ByteRange.Unsatisifiable impossible) {
			return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
				.header(HttpHeaders.CONTENT_RANGE, "bytes */" + total)
				.header(HttpHeaders.ETAG, etag)
				.build();
		}
		if (range.isPresent()) {
			ByteRange bytes = range.get();
			response = ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
				.header(HttpHeaders.CONTENT_RANGE, bytes.contentRange())
				.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(bytes.length()))
				.header(HttpHeaders.ACCEPT_RANGES, "bytes");
			long skip = bytes.start();
			long limit = bytes.length();
			ResponseEntity.BodyBuilder partial = response;
			return finish(partial, object, policy, etag)
				.body(output -> writeSlice(storage.readBody(object.id()), output, skip, limit));
		}
		// Corpo inteiro: 200 com Accept-Ranges para o cliente saber que pode pedir faixas depois.
		ResponseEntity.BodyBuilder whole = ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(total))
			.header(HttpHeaders.ACCEPT_RANGES, "bytes");
		return finish(whole, object, policy, etag).body(output -> writeBody(storage.readBody(object.id()), output));
	}

	/** Só as informações de cabeçalho — o que o {@code HEAD} do S3 precisa, sem corpo. */
	public ResponseEntity<Void> head(StorageObject object) {
		List<StoragePlacement> location = storage.location(object.id());
		if (location.isEmpty()) {
			throw new ApiException(409, "STORAGE_PLACEMENT_MISSING",
				"O catalogo diz que o objeto existe, mas nao ha copia registrada em no nenhum.");
		}
		CachePolicy policy = CachePolicy.forContentType(object.contentType());
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_TYPE, object.contentType())
			.header(HttpHeaders.CACHE_CONTROL, policy.headerValue())
			.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(object.bytes()))
			.header(HttpHeaders.ACCEPT_RANGES, "bytes")
			.eTag(etag(object))
			.lastModified(object.updatedAt() == null ? null : object.updatedAt().toEpochMilli())
			.build();
	}

	private static ResponseEntity.BodyBuilder finish(ResponseEntity.BodyBuilder builder, StorageObject object,
			CachePolicy policy, String etag) {
		return builder.header(HttpHeaders.CONTENT_TYPE, object.contentType())
			.header(HttpHeaders.CACHE_CONTROL, policy.headerValue())
			.eTag(etag)
			.lastModified(object.updatedAt() == null ? null : object.updatedAt().toEpochMilli());
	}

	/** {@code If-None-Match} contra o ETag; {@code *} e a lista separada por vírgula valem. */
	static boolean matches(String etag, String ifNoneMatch) {
		if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
			return false;
		}
		String wanted = etag.replace("W/", "");
		for (String candidate : ifNoneMatch.split(",")) {
			String item = candidate.trim().replace("W/", "");
			if ("*".equals(item) || wanted.equals(item)) {
				return true;
			}
		}
		return false;
	}

	private static void writeSlice(InputStream body, OutputStream output, long skip, long length) throws IOException {
		try (InputStream input = body) {
			long remaining = skip;
			while (remaining > 0) {
				long skipped = input.skip(remaining);
				if (skipped <= 0) {
					int read = input.read();
					if (read < 0) {
						return;
					}
					skipped = 1;
				}
				remaining -= skipped;
			}
			byte[] buffer = new byte[8192];
			long left = length;
			while (left > 0) {
				int read = input.read(buffer, 0, (int) Math.min(buffer.length, left));
				if (read < 0) {
					return;
				}
				output.write(buffer, 0, read);
				left -= read;
			}
		}
	}

	private static void writeBody(InputStream body, OutputStream output) throws IOException {
		try (InputStream input = body) {
			input.transferTo(output);
		}
	}
}