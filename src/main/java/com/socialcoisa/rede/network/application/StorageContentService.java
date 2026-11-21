package com.socialcoisa.rede.network.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
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

	/*
	 * ------------------------------------------------------------------------------------------
	 * Contrato dos métodos abaixo, e por que ele não é o do Spring:
	 *
	 * `ResponseEntity<...>` do Spring MVC faz 304 SOZINHO quando a requisição é GET/HEAD, removendo
	 * ETag e Last-Modified da resposta antes de comparar. Com um corpo `StreamingResponseBody` isso
	 * mata a resposta SEM despacho assíncrono: o cliente recebe 304 sem `ETag` nem `Last-Modified`,
	 * e um teste que lê a resposta antes do despacho vê um 200. E só existe para If-None-Match /
	 * If-Modified-Since na forma que ele sabe comparar, sem as regras do S3 (por exemplo, um ETag
	 * diferente deve desligar a data).
	 *
	 * Por isso as respostas de objeto saem em `ResponseEntity<Object>` com STATUS EXPLÍCITO
	 * (`HttpStatusCode`, que o Spring não intercede), e quem compara os condicionais é esta classe —
	 * o mesmo código para a rota pública e para o gateway S3. A regra do 304 fica num lugar só.
	 * ------------------------------------------------------------------------------------------
	 */

	/**
	 * A resposta HTTP pronta, com status já decidido: {@code 304}, {@code 206}, {@code 416} ou
	 * {@code 200}, e o corpo por streaming. O status é numérico de propósito — ver {@link #resposta}.
	 *
	 * <p><b>Ordem dos validadores (RFC 9110 §13.1.3):</b> quando o pedido traz {@code If-None-Match},
	 * só o ETag decide — o {@code If-Modified-Since} é <b>ignorado</b>. Sem {@code If-None-Match}, a
	 * data decide.</p>
	 */
	public ResponseEntity<StreamingResponseBody> respond(StorageObject object, String rangeHeader, String ifNoneMatch,
			String ifModifiedSince) {
		List<StoragePlacement> location = storage.location(object.id());
		if (location.isEmpty()) {
			throw new ApiException(409, "STORAGE_PLACEMENT_MISSING",
				"O catalogo diz que o objeto existe, mas nao ha copia registrada em no nenhum.");
		}
		long total = object.bytes();
		CachePolicy policy = CachePolicy.forContentType(object.contentType());
		String etag = etag(object);

		// Pedido condicional: o cliente que já tem o conteúdo não paga o corpo de novo.
		if (notModified(object, etag, ifNoneMatch, ifModifiedSince)) {
			return respostaEncontrada(object, etag, policy).build();
		}

		ResponseEntity.BodyBuilder response;
		Optional<ByteRange> range;
		try {
			range = ByteRange.parse(rangeHeader, total);
		}
		catch (ByteRange.Unsatisifiable impossible) {
			return resposta(416).header(HttpHeaders.CONTENT_RANGE, "bytes */" + total)
				.header(HttpHeaders.ETAG, etag)
				.build();
		}
		if (range.isPresent()) {
			ByteRange bytes = range.get();
			response = ResponseEntity.status(HttpStatusCode.valueOf(206))
				.header(HttpHeaders.CONTENT_RANGE, bytes.contentRange())
				.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(bytes.length()))
				.header(HttpHeaders.ACCEPT_RANGES, "bytes");
			long skip = bytes.start();
			long limit = bytes.length();
			ResponseEntity.BodyBuilder parcial = response;
			return finish(parcial, object, policy, etag)
				.body((StreamingResponseBody) output -> writeSlice(storage.readBody(object.id()), output, skip, limit));
		}
		// Corpo inteiro: 200 com Accept-Ranges para o cliente saber que pode pedir faixas depois.
		ResponseEntity.BodyBuilder inteiro = resposta(200)
			.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(total))
			.header(HttpHeaders.ACCEPT_RANGES, "bytes");
		return finish(inteiro, object, policy, etag)
			.body((StreamingResponseBody) output -> writeBody(storage.readBody(object.id()), output));
	}

	/**
	 * Só as informações de cabeçalho — o que o {@code HEAD} do S3 precisa, sem corpo.
	 *
	 * <p>Condicional também: <b>304</b> quando o validador do cliente já corresponde. O SDK usa o
	 * HEAD para decidir se baixa o objeto; 200 sempre é "baixe de novo", mesmo com os bytes em mãos.</p>
	 */
	public ResponseEntity<StreamingResponseBody> head(StorageObject object, String ifNoneMatch, String ifModifiedSince) {
		List<StoragePlacement> location = storage.location(object.id());
		if (location.isEmpty()) {
			throw new ApiException(409, "STORAGE_PLACEMENT_MISSING",
				"O catalogo diz que o objeto existe, mas nao ha copia registrada em no nenhum.");
		}
		CachePolicy policy = CachePolicy.forContentType(object.contentType());
		String etag = etag(object);
		if (notModified(object, etag, ifNoneMatch, ifModifiedSince)) {
			return respostaEncontrada(object, etag, policy).build();
		}
		ResponseEntity.BodyBuilder builder = resposta(200)
			.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(object.bytes()))
			.header(HttpHeaders.ACCEPT_RANGES, "bytes");
		return finish(builder, object, policy, etag).build();
	}

	/**
	 * O {@code Content-Disposition} e o metadado do objeto (V367).
	 *
	 * <p>O {@code Content-Disposition} é o que faz {@code GET} devolver o nome de arquivo que o cliente
	 * escolheu — antes o anexo vinha sem nome. O {@code Cache-Control} fica em {@link #cacheControl},
	 * porque é lá que ele disputa com a política do tipo e um dos dois tem de vencer.</p>
	 */
	private static void declarados(ResponseEntity.BodyBuilder builder, StorageObject object) {
		if (object.contentDisposition() != null && !object.contentDisposition().isBlank()) {
			builder.header(HttpHeaders.CONTENT_DISPOSITION, object.contentDisposition());
		}
		for (java.util.Map.Entry<String, String> meta : object.metadata().values().entrySet()) {
			// O nome já foi validado no upload (alfabeto do cabeçalho); o valor é o que o cliente
			// guardou, e o que voltou no PUT é o que ele procura no HEAD.
			builder.header("x-amz-meta-" + meta.getKey(), meta.getValue());
		}
	}

	/**
	 * Os cabeçalhos comuns ao GET (200 e 206) e ao HEAD: o que o cliente declarou e, por cima, o tipo,
	 * o cache, o {@code ETag} e a data. Um lugar só porque um deles esquecer o metadado é o cliente
	 * receber o objeto diferente no HEAD e no GET — e é o HEAD que o SDK usa para decidir.
	 */
	private static ResponseEntity.BodyBuilder finish(ResponseEntity.BodyBuilder builder, StorageObject object,
			CachePolicy policy, String etag) {
		declarados(builder, object);
		return builder.header(HttpHeaders.CONTENT_TYPE, object.contentType())
			.header(HttpHeaders.CACHE_CONTROL, cacheControl(object, policy))
			.eTag(etag)
			.lastModified(object.updatedAt() == null ? null : object.updatedAt().toEpochMilli());
	}

	/**
	 * O {@code Cache-Control} que sai: o declarado pelo cliente, ou a política do tipo.
	 *
	 * <p>Um valor só, nunca os dois: cabeçalho repetido deixa o cache escolher o que quiser, e o
	 * declarado só perde quando não existe.</p>
	 */
	private static String cacheControl(StorageObject object, CachePolicy policy) {
		return object.cacheControlOrNull() != null ? object.cacheControlOrNull() : policy.headerValue();
	}

	/**
	 * Builder com STATUS EXPLÍCITO ({@code HttpStatusCode}), não com os atalhos {@code ok()}/{@code
	 * notModified()}.
	 *
	 * <p>O Spring MVC faz 304 sozinho em GET/HEAD comparando as requisições internamente, e nesse
	 * caminho remove {@code ETag} e {@code Last-Modified} e não despacha um corpo
	 * {@code StreamingResponseBody} — a resposta condicional sai sem despacho. Com o status explícito
	 * o Spring não intercede, e quem decide o 304 é {@link #notModified}, com as regras do S3.
	 * Por isso toda resposta de objeto desta classe nasce aqui.</p>
	 */
	static ResponseEntity.BodyBuilder resposta(int status) {
		return ResponseEntity.status(HttpStatusCode.valueOf(status));
	}

	/** O 304 completo: {@code ETag}, a mesma política de cache e o {@code Last-Modified}. */
	private static ResponseEntity.BodyBuilder respostaEncontrada(StorageObject object, String etag,
			CachePolicy policy) {
		ResponseEntity.BodyBuilder notModified = resposta(304)
			.header(HttpHeaders.ETAG, etag)
			.header(HttpHeaders.CACHE_CONTROL, policy.headerValue());
		if (object.updatedAt() != null) {
			notModified.lastModified(object.updatedAt().toEpochMilli());
		}
		return notModified;
	}

	/**
	 * O pedido condicional bate? {@code If-None-Match} primeiro; sem ele, {@code If-Modified-Since}.
	 *
	 * <p><b>A data é comparada em segundos</b>, a granularidade do HTTP-date: o {@code updated_at} tem
	 * milissegundos, e um cliente devolve exatamente o {@code Last-Modified} que recebeu. Comparar
	 * sem truncar diria "mudou" sempre — o 304 por data nunca aconteceria.</p>
	 *
	 * <p>Data malformada <b>não</b> dá erro: é ignorada, como um cabeçalho ausente (RFC 9110 §13.1.3
	 * manda ignorar campo de data que não é data). Recusar a leitura por causa de um validador torto
	 * quebraria o cliente que, de todo modo, receberia o corpo inteiro.</p>
	 */
	public static boolean notModified(StorageObject object, String etag, String ifNoneMatch,
			String ifModifiedSince) {
		if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
			// Com If-None-Match presente, a data é ignorada — inclusive quando o ETag não bate.
			return matches(etag, ifNoneMatch);
		}
		if (object.updatedAt() == null || ifModifiedSince == null || ifModifiedSince.isBlank()) {
			return false;
		}
		java.time.Instant pedido;
		try {
			pedido = java.time.ZonedDateTime
				.parse(ifModifiedSince.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
				.toInstant();
		}
		catch (java.time.format.DateTimeParseException invalida) {
			return false;
		}
		return !object.updatedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
			.isAfter(pedido.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
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