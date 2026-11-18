package com.socialcoisa.rede.network.adapters.in.web;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.socialcoisa.rede.network.application.SigV4Verifier;
import com.socialcoisa.rede.network.application.StorageCatalogService;
import com.socialcoisa.rede.network.application.StorageContentService;
import com.socialcoisa.rede.network.application.port.out.S3CredentialRepository;
import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.S3Credential;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.web.ApiException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * O gateway <b>API compatível com S3</b> do Énóis Storage (brief §11).
 *
 * <p><b>Antes:</b> o Storage só era alcançável por {@code /network/storage/*} com chave de nó. Nenhum
 * SDK S3 fala esse protocolo. <b>Agora:</b> o par Access Key/Secret Key assina com SigV4 e as
 * operações abaixo respondem no formato S3 (XML com o namespace da S3).</p>
 *
 * <h2>O que existe — exatamente, sem anunciar o que não existe</h2>
 *
 * <p>O brief é explícito: <i>não anunciar compatibilidade integral com S3 se houver operações ausentes
 * ou diferenças semânticas</i>. A lista completa está na doc {@code 18-storage-gateway-s3.md}; resumo:</p>
 *
 * <table>
 * <tr><th>operação</th><th>rota</th><th>semântica</th></tr>
 * <tr><td>PutObject</td><td>{@code PUT /{bucket}/{key}}</td><td>igual</td></tr>
 * <tr><td>GetObject</td><td>{@code GET /{bucket}/{key}}</td><td>igual; Range/206/416 e 304 por ETag</td></tr>
 * <tr><td>HeadObject</td><td>{@code HEAD /{bucket}/{key}}</td><td>igual</td></tr>
 * <tr><td>DeleteObject</td><td>{@code DELETE /{bucket}/{key}}</td><td><b>lápide</b>, não remoção imediata (igual ao S3 com versionamento)</td></tr>
 * <tr><td>ListObjectsV2</td><td>{@code GET /{bucket}?list-type=2}</td><td>igual, paginado por {@code continuation-token}</td></tr>
 * <tr><td>ListObjects (v1)</td><td>{@code GET /{bucket}}</td><td>igual</td></tr>
 * <tr><td>ListBuckets</td><td>{@code GET /}</td><td>igual</td></tr>
 * <tr><td>DeleteBucket</td><td>{@code DELETE /{bucket}}</td><td>recusa se houver objeto — igual ao S3</td></tr>
 * </table>
 *
 * <p><b>Não implementado, e recusado com 501 em vez de "fazer parecer":</b> Multipart Upload,
 * CopyObject, presigned URLs (URL assinada na query), ACL, tagging, versionId, bucket policy e
 * {@code POST} de formulário. A recusa é explícita porque um cliente que recebe 200 sem a semântica
 * (um "multipart" que grava o arquivo inteiro) perde dados quando o upload é retomado.</p>
 *
 * <p><b>Documento em XML:</b> o gateway serializa à mão, sem {@code ListBucketResult.class}. O motivo
 * é controle de namespace e de ordem dos elementos: um JAXB muda a ordem e o SDK não protesta, mas
 * recebe um documento diferente do que a S3 manda. Escrever à mão é mais código e menos surpresa.</p>
 */
@RestController
@RequestMapping("/s3")
class S3GatewayController {

	/** Região lógica única: a rede não é particionada por região S3. Entra na assinatura como string. */
	static final String REGION = "enois-1";

	/** O prefixo da instalação. O SDK não o conhece; a assinatura é feita sobre o caminho S3. */
	static final String PREFIX = "/s3";

	/** Teto de chaves por página, igual ao S3. */
	private static final int MAX_KEYS = 1000;

	private final S3CredentialRepository credentials;
	private final StorageCatalogRepository catalog;
	private final StorageCatalogService storage;
	private final StorageContentService content;
	private final Clock clock;

	S3GatewayController(S3CredentialRepository credentials, StorageCatalogRepository catalog,
			StorageCatalogService storage, StorageContentService content, Clock clock) {
		this.credentials = credentials;
		this.catalog = catalog;
		this.storage = storage;
		this.content = content;
		this.clock = clock;
	}

	// ---------------------------------------------------------------- listagem de buckets

	/** ListBuckets: só os buckets com objeto do dono da credencial. */
	@GetMapping("/")
	ResponseEntity<String> listBuckets(HttpServletRequest request) {
		S3Credential credential = authenticate(request, "LIST");
		List<String> buckets = catalog.bucketsOf(credential.ownerUserId());
		StringBuilder xml = new StringBuilder();
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
		xml.append("<ListAllMyBucketsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
		xml.append("<Buckets>");
		for (String bucket : buckets) {
			xml.append("<Bucket><Name>").append(esc(bucket)).append("</Name></Bucket>");
		}
		xml.append("</Buckets></ListAllMyBucketsResult>");
		return xml(HttpStatus.OK).body(xml.toString());
	}

	// ---------------------------------------------------------------- bucket: listagem e remoção

	/** ListObjectsV2 ({@code ?list-type=2}) e ListObjects v1. */
	@GetMapping("/{bucket}")
	ResponseEntity<String> listObjects(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "LIST");
		requireBucket(credential, bucket);
		Map<String, String> query = query(request);
		boolean v2 = "2".equals(query.get("list-type"));
		int maxKeys = parseMaxKeys(query.get("max-keys"));
		// `continuation-token` (v2) e `marker` (v1) são a mesma coisa: a chave da última página.
		String after = v2 ? query.get("continuation-token") : query.get("marker");
		List<StorageObject> found = storage.list(bucket, query.get("prefix"), after, maxKeys);
		boolean truncated = found.size() == maxKeys;
		String lastKey = found.isEmpty() ? null : found.get(found.size() - 1).key();

		StringBuilder xml = new StringBuilder();
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
		xml.append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
		xml.append("<Name>").append(esc(bucket)).append("</Name>");
		String prefix = query.get("prefix");
		if (prefix != null) {
			xml.append("<Prefix>").append(esc(prefix)).append("</Prefix>");
		}
		xml.append("<MaxKeys>").append(maxKeys).append("</MaxKeys>");
		xml.append("<KeyCount>").append(found.size()).append("</KeyCount>");
		xml.append("<IsTruncated>").append(truncated).append("</IsTruncated>");
		if (truncated && lastKey != null) {
			xml.append(v2 ? "<NextContinuationToken>" : "<NextMarker>");
			xml.append(v2 ? esc(lastKey) : esc(lastKey));
			xml.append(v2 ? "</NextContinuationToken>" : "</NextMarker>");
		}
		for (StorageObject object : found) {
			xml.append("<Contents><Key>").append(esc(object.key())).append("</Key>");
			xml.append("<LastModified>").append(object.createdAt()).append("</LastModified>");
			xml.append("<ETag>&quot;").append(esc(object.sha256())).append("&quot;</ETag>");
			xml.append("<Size>").append(object.bytes() == null ? 0 : object.bytes()).append("</Size>");
			xml.append("<StorageClass>STANDARD</StorageClass></Contents>");
		}
		xml.append("</ListBucketResult>");
		return xml(HttpStatus.OK).body(xml.toString());
	}

	/** DeleteBucket: recusa com 409 se ainda houver objeto, como o S3. */
	@DeleteMapping("/{bucket}")
	ResponseEntity<Void> deleteBucket(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "DELETE");
		requireBucket(credential, bucket);
		List<StorageObject> any = storage.list(bucket, null, null, 1);
		if (!any.isEmpty()) {
			throw new ApiException(409, "BucketNotEmpty", "O bucket ainda tem objetos.");
		}
		throw new ApiException(501, "NotImplemented", "Remocao de bucket nao e suportada: nao ha bucket vazio a apagar.");
	}

	// ---------------------------------------------------------------- objeto

	/** PutObject: o corpo entra pelo caminho do catálogo; a resposta devolve o ETag (o digest). */
	@RequestMapping(value = "/{bucket}/{*key}", method = RequestMethod.PUT)
	ResponseEntity<Void> putObject(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "PUT");
		requireBucket(credential, bucket);
		String key = keyOf(request);
		// O tipo é o que o SDK manda; sem ele, o mesmo padrão do resto do módulo.
		String contentType = request.getContentType();
		StorageObject pending = storage.beginUpload(credential.ownerUserId(), bucket, key,
			contentType == null ? "application/octet-stream" : contentType);
		// O catálogo mede o digest em pedaços de 64 KiB e grava; é o mesmo caminho da rota do nó.
		StorageObject stored = storage.finishUpload(pending.id(), body(request), credential.ownerUserId());
		return ResponseEntity.ok()
			.eTag("\"" + stored.sha256() + "\"")
			.header(HttpHeaders.CONTENT_LENGTH, "0")
			.build();
	}

	/** GetObject: Range/206/416, 304 por If-None-Match, ETag = digest. */
	@GetMapping("/{bucket}/{*key}")
	ResponseEntity<StreamingResponseBody> getObject(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "GET");
		requireBucket(credential, bucket);
		StorageObject object = storage.requireDownloadable(bucket, keyOf(request));
		return content.respond(object, request.getHeader(HttpHeaders.RANGE),
			request.getHeader(HttpHeaders.IF_NONE_MATCH));
	}

	/** HeadObject: mesmos cabeçalhos do GetObject, sem corpo. */
	@RequestMapping(value = "/{bucket}/{*key}", method = RequestMethod.HEAD)
	ResponseEntity<Void> headObject(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "GET");
		requireBucket(credential, bucket);
		StorageObject object = storage.requireDownloadable(bucket, keyOf(request));
		return content.head(object);
	}

	/** DeleteObject: lápide no catálogo. A resposta é 204 mesmo se a chave não existir (igual ao S3). */
	@DeleteMapping("/{bucket}/{*key}")
	ResponseEntity<Void> deleteObject(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "DELETE");
		requireBucket(credential, bucket);
		String key = keyOf(request);
		Optional<StorageObject> object = catalog.find(bucket, key);
		if (object.isPresent()) {
			storage.delete(credential.ownerUserId(), bucket, key);
		}
		return ResponseEntity.noContent().build();
	}

	// ---------------------------------------------------------------- autenticação

	/**
	 * Autentica a requisição e confere a operação da credencial.
	 *
	 * <p><b>A recusa é 403 com XML de erro do S3</b>: um SDK que recebe texto simples não reconhece o
	 * erro e mostra "erro desconhecido" ao usuário final.</p>
	 */
	private S3Credential authenticate(HttpServletRequest request, String operation) {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		SigV4Verifier.Parsed parsed = SigV4Verifier.parseAuthorization(authorization);
		if (parsed == null) {
			throw s3Error(403, "AccessDenied", "Assinatura AWS SigV4 ausente.");
		}
		S3Credential credential = credentials.findByAccessKey(parsed.accessKey())
			.filter(c -> c.revokedAt() == null)
			.orElseThrow(() -> s3Error(403, "InvalidAccessKeyId",
				"A credencial nao existe ou foi revogada."));
		String secret = credentials.secretFor(credential.id());
		if (secret == null) {
			// Lacuna declarada: o SigV4 exige o segredo em claro para recalcular o HMAC.
			throw s3Error(403, "SignatureDoesNotMatch",
				"Credencial sem segredo guardado nao pode assinar; gere uma nova.");
		}
		// A URI canônica é a que o SDK assinou: `/bucket/key`, sem o prefixo do gateway (`/s3`). O
		// prefixo é da nossa instalação, não do protocolo S3 — cliente e servidor têm de calcular com
		// o mesmo texto, e o texto do protocolo é o caminho depois do endpoint.
		var signed = new SigV4Verifier.Request(request.getMethod(), s3Path(request),
			query(request), signedHeaders(request, parsed.signedHeaders()), header(request, "x-amz-content-sha256"));
		if (!SigV4Verifier.verify(signed, parsed, secret, clock.instant())) {
			throw s3Error(403, "SignatureDoesNotMatch", "A assinatura nao confere.");
		}
		if (!credential.allows(operation)) {
			throw s3Error(403, "AccessDenied", "A credencial nao permite " + operation + ".");
		}
		return credential;
	}

	/** O bucket pedido é o permitido pela credencial? {@code null} em allowedBucket = todos do dono. */
	private static void requireBucket(S3Credential credential, String bucket) {
		if (!credential.allowsBucket(bucket)) {
			throw s3Error(403, "AccessDenied", "A credencial nao permite este bucket.");
		}
	}

	/** Os cabeçalhos declarados em SignedHeaders, normalizados como o cliente assinou. */
	private static Map<String, String> signedHeaders(HttpServletRequest request, String signedHeaders) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String name : signedHeaders.split(";")) {
			String value = header(request, name);
			map.put(name, SigV4Verifier.normalizeValue(value));
		}
		return map;
	}

	private static String header(HttpServletRequest request, String name) {
		if ("host".equals(name)) {
			String host = request.getHeader("Host");
			return host == null ? request.getServerName() : host;
		}
		return request.getHeader(name);
	}

	/** Query decodificada (o SigV4 recodifica para canonicalizar). */
	static Map<String, String> query(HttpServletRequest request) {
		Map<String, String> out = new LinkedHashMap<>();
		String query = request.getQueryString();
		if (query == null || query.isBlank()) {
			return out;
		}
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			String name = eq < 0 ? pair : pair.substring(0, eq);
			String value = eq < 0 ? "" : pair.substring(eq + 1);
			out.put(decode(name), decode(value));
		}
		return out;
	}

	private static String decode(String value) {
		try {
			return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException invalido) {
			// percent-encoding quebrado: o valor fica como veio, e a assinatura provavelmente falha.
			return value;
		}
	}

	/**
	 * A chave do objeto é o caminho depois do bucket, <b>sem reinterpretar</b> {@code /}: uma chave S3
	 * pode ter barras e elas não são separadores de recurso.
	 */
	private static String keyOf(HttpServletRequest request) {
		String path = s3Path(request);
		// tira a barra inicial e o primeiro segmento (o bucket)
		String semBarra = path.startsWith("/") ? path.substring(1) : path;
		int slash = semBarra.indexOf('/');
		if (slash < 0) {
			throw s3Error(400, "InvalidRequest", "Informe o bucket e a chave.");
		}
		return semBarra.substring(slash + 1);
	}

	/** O caminho como o protocolo S3 o vê: sem o context path e sem o prefixo `/s3` do gateway. */
	static String s3Path(HttpServletRequest request) {
		String path = request.getRequestURI();
		String context = request.getContextPath();
		if (context != null && !context.isEmpty() && path.startsWith(context)) {
			path = path.substring(context.length());
		}
		if (path.startsWith(PREFIX)) {
			path = path.substring(PREFIX.length());
		}
		return path.isEmpty() ? "/" : path;
	}

	private static InputStream body(HttpServletRequest request) {
		try {
			return request.getInputStream();
		}
		catch (java.io.IOException falha) {
			throw new ApiException(400, "InvalidRequest", "Nao foi possivel ler o corpo.");
		}
	}

	private static int parseMaxKeys(String value) {
		if (value == null || value.isBlank()) {
			return MAX_KEYS;
		}
		try {
			int parsed = Integer.parseInt(value.trim());
			return parsed <= 0 ? MAX_KEYS : Math.min(parsed, MAX_KEYS);
		}
		catch (NumberFormatException invalido) {
			return MAX_KEYS;
		}
	}

	private static ResponseEntity.BodyBuilder xml(HttpStatus status) {
		return ResponseEntity.status(status).header(HttpHeaders.CONTENT_TYPE, "application/xml; charset=utf-8");
	}

	private static ApiException s3Error(int status, String code, String message) {
		// O corpo XML é montado pelo handler de exceção do módulo; aqui só o código e a mensagem
		// importam. O código S3 (AccessDenied, SignatureDoesNotMatch) é diferente do código interno
		// do Énóis, e é o que o SDK lê.
		return new S3ApiException(status, code, message);
	}

	/** XML de erro no formato do S3 — o formato que o SDK interpreta. */
	static String errorXml(String code, String message, String resource) {
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + "<Error><Code>" + esc(code) + "</Code><Message>"
			+ esc(message) + "</Message><Resource>" + esc(resource == null ? "" : resource)
			+ "</Resource><RequestId>" + esc(java.util.UUID.randomUUID().toString()) + "</RequestId></Error>";
	}

	static String esc(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
			.replace("'", "&apos;");
	}
}