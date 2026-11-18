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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 * <tr><td>URL pré-assinada</td><td>{@code POST /{bucket}/presign}</td><td>emite para GET/PUT; uso sem cabeçalho {@code Authorization}</td></tr>
 * </table>
 *
 * <p><b>Não implementado, e recusado com 501 em vez de "fazer parecer":</b> Multipart Upload,
 * CopyObject, ACL, tagging, versionId, bucket policy e
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

	/** A data assinada, no formato do SigV4. */
	private static final java.time.format.DateTimeFormatter AMZ_DATE = java.time.format.DateTimeFormatter
		.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC);

	/** Teto de chaves por página, igual ao S3. */
	private static final int MAX_KEYS = 1000;

	private final S3CredentialRepository credentials;
	private final StorageCatalogRepository catalog;
	private final StorageCatalogService storage;
	private final StorageContentService content;
	private final Clock clock;
	private final com.socialcoisa.rede.shared.web.CurrentUserResolver currentUserResolver;

	S3GatewayController(S3CredentialRepository credentials, StorageCatalogRepository catalog,
			StorageCatalogService storage, StorageContentService content, Clock clock,
			com.socialcoisa.rede.shared.web.CurrentUserResolver currentUserResolver) {
		this.credentials = credentials;
		this.catalog = catalog;
		this.storage = storage;
		this.content = content;
		this.clock = clock;
		this.currentUserResolver = currentUserResolver;
	}

	// ---------------------------------------------------------------- listagem de buckets

	/** ListBuckets: só os buckets com objeto do dono da credencial. */
	@GetMapping("/")
	ResponseEntity<String> listBuckets(HttpServletRequest request) {
		S3Credential credential = authenticate(request, "LIST");
		List<String> buckets = catalog.bucketsOf(credential.ownerUserId());
		S3Xml xml = new S3Xml();
		xml.root("ListAllMyBucketsResult").open("Buckets");
		for (String bucket : buckets) {
			xml.open("Bucket").element("Name", bucket).close("Bucket");
		}
		xml.close("Buckets").close("ListAllMyBucketsResult");
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

		S3Xml xml = new S3Xml();
		xml.root("ListBucketResult").element("Name", bucket);
		String prefix = query.get("prefix");
		if (prefix != null) {
			xml.element("Prefix", prefix);
		}
		xml.element("MaxKeys", maxKeys).element("KeyCount", found.size()).element("IsTruncated", truncated);
		if (truncated && lastKey != null) {
			xml.element(v2 ? "NextContinuationToken" : "NextMarker", lastKey);
		}
		for (StorageObject object : found) {
			xml.open("Contents").element("Key", object.key())
				.element("LastModified", String.valueOf(object.createdAt()))
				.element("ETag", "\"" + (object.sha256() == null ? "" : object.sha256()) + "\"")
				.element("Size", object.bytes() == null ? 0 : object.bytes()).element("StorageClass", "STANDARD")
				.close("Contents");
		}
		xml.close("ListBucketResult");
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
	/**
	 * Autentica uma <b>URL pré-assinada</b> (brief §11) e devolve a credencial, ou {@code null} quando a
	 * requisição não carrega {@code X-Amz-Signature}.
	 *
	 * <p>O escopo é o da credencial, como no cabeçalho: uma URL de {@code GET} não concede {@code PUT}.
	 * A URL reduz o que o portador pode fazer, nunca amplia — quem gera escolhe o verbo, e o verbo
	 * declarado não é o verbo da URL: ele é o que o cliente usar, e o escopo da credencial manda.</p>
	 */
	private S3Credential authenticatePresigned(HttpServletRequest request, String operation) {
		// O cliente assinou o `Host` que ELE viu; o do servidor pode trazer o prefixo da instalação.
		// Normalizar aqui é o que faz a assinatura do emissor e a do portador coincidirem.
		String host = header(request, "host");
		String hostAssinado = host.endsWith(PREFIX) ? host.substring(0, host.length() - PREFIX.length()) : host;
		var presigned = SigV4Verifier.parsePresigned(
			new SigV4Verifier.Request(request.getMethod(), s3Path(request), query(request),
				Map.of("host", hostAssinado), null),
			clock.instant());
		if (presigned == null) {
			return null;
		}
		S3Credential credential = credentials.findByAccessKey(presigned.parsed().accessKey())
			.filter(c -> c.revokedAt() == null)
			.orElseThrow(() -> s3Error(403, "InvalidAccessKeyId",
				"A credencial da URL nao existe ou foi revogada."));
		String secret = credentials.secretFor(credential.id());
		if (secret == null) {
			throw s3Error(403, "SignatureDoesNotMatch",
				"Credencial sem segredo guardado nao pode assinar; gere uma nova.");
		}
		if (!SigV4Verifier.verifyPresigned(presigned, secret, clock.instant())) {
			// Um só código para URL expirada e assinatura errada, como na AWS quando o prazo passou:
			// dizer qual dos dois é ajudaria a descobrir o segredo, e o cliente resolve os dois do
			// mesmo jeito — pedindo outra URL.
			throw s3Error(403, "AccessDenied",
				"A URL pre-assinada expirou"
					+ (presigned.expiraEm().isBefore(clock.instant()) ? " (validade vencida)" : "")
					+ " ou a assinatura nao confere.");
		}
		if (!credential.allows(operation)) {
			throw s3Error(403, "AccessDenied", "A credencial da URL nao permite " + operation + ".");
		}
		return credential;
	}

	/**
	 * Emite uma URL pré-assinada ({@code POST /s3/{bucket}/presign?key=fotos%2Fgato.jpg}).
	 *
	 * <p>A chave vem em parâmetro, e não no caminho: o caminho de objeto do gateway é
	 * {@code /{bucket}/{*key}}, um segmento que engole o resto — uma rota {@code /{bucket}/{*key}/presigned}
	 * colide com ele no mesmo verbo {@code POST} e o MVC não consegue escolher entre as duas. Como a
	 * chave já vai codificada na URL emitida, pedi-la em parâmetro custa uma codificação a quem emite.</p>
	 *
	 * <p>Quem emite é quem tem o segredo — o Control Plane, autenticado pela sessão do dono, não por
	 * credencial S3. A URL carrega {@code X-Amz-Signature} e vale até expirar; a credencial nunca sai
	 * daqui, que é o motivo de a URL existir (o navegador recebe uma URL curta, não uma chave).</p>
	 *
	 * <p><b>Verbo:</b> só {@code GET} e {@code PUT}. {@code DELETE} por URL é o mesmo que dar a lápide
	 * a qualquer portador de um link vazado, e {@code LIST} por URL vaza a listagem do bucket para quem
	 * recebia um objeto só.</p>
	 */
	@PostMapping("/{bucket}/presign")
	Map<String, Object> presign(@PathVariable String bucket,
			@RequestParam(required = false) String key,
			@RequestParam(defaultValue = "GET") String method,
			@RequestParam(defaultValue = "900") long expires,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			HttpServletRequest request) {
		// Sessão primeiro, parâmetro depois: a rota vive sob `/s3` e responde no vocabulário de erro do
		// S3. Recusar pelo parâmetro ausente antes da autenticação diria a quem não está logado qual é
		// a forma da chamada e devolveria 400 em vez do 403 do protocolo.
		com.socialcoisa.rede.shared.web.CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (key == null || key.isBlank()) {
			throw s3Error(400, "InvalidArgument", "Informe a chave do objeto.");
		}
		String verbo = method.toUpperCase(Locale.ROOT);
		if (!"GET".equals(verbo) && !"PUT".equals(verbo)) {
			throw s3Error(400, "InvalidArgument", "URL pre-assinada so para GET ou PUT.");
		}
		if (expires <= 0 || expires > SigV4Verifier.MAX_PRESIGNED_SECONDS) {
			throw s3Error(400, "InvalidArgument",
				"Expira em segundos entre 1 e " + SigV4Verifier.MAX_PRESIGNED_SECONDS + ".");
		}
		// O bucket tem de ser DESTE dono. Não basta o escopo da credencial: `allowedBucket == null`
		// significa "todos os buckets do dono", não "todos os buckets da rede" — e uma URL emitida
		// sai das mãos do dono, então o escopo ilimitado não pode virar um link para o bucket de
		// outra pessoa. A lista de buckets do dono já existe no catálogo (a mesma do ListBuckets).
		boolean bucketDoDono = catalog.bucketsOf(user.id().value()).contains(bucket);
		if (!bucketDoDono) {
			throw s3Error(403, "AccessDenied", "Este bucket nao e do dono da sessao.");
		}
		// E a credencial escolhida tem de permitir o verbo E o bucket — escopo nulo passa aqui
		// porque a donagem do bucket acima já foi conferida.
		S3Credential credential = credentials.findByOwner(user.id().value(), 20)
			.stream()
			.filter(c -> c.revokedAt() == null && c.allows(verbo))
			.filter(c -> c.allowedBucket() == null || c.allowedBucket().equals(bucket))
			.findFirst()
			.orElseThrow(() -> s3Error(403, "AccessDenied",
				"Nao ha credencial do dono que permita " + verbo + " neste bucket."));
		String secret = credentials.secretFor(credential.id());
		if (secret == null) {
			throw s3Error(503, "ServiceUnavailable",
				"Credencial sem segredo guardado nao pode emitir URL; gere uma nova.");
		}
		return presignedUrl(credential, secret, bucket, key, verbo, expires, request);
	}

	private Map<String, Object> presignedUrl(S3Credential credential, String secret, String bucket, String key,
			String verbo, long expires, HttpServletRequest request) {
		String amzDate = AMZ_DATE.format(clock.instant());
		String dateStamp = amzDate.substring(0, 8);
		// O `host` que o cliente mandou já pode trazer o prefixo da instalação (`s3.enois.ia.br/s3`):
		// concatenar outro daria `/s3/s3/bucket`. Um só `PREFIX` na URL, e o host sem ele.
		// O `host` que o cliente mandou pode já trazer o prefixo da instalação (`s3.enois.ia.br/s3`):
		// concatenar outro daria `/s3/s3/bucket`. Um só `PREFIX` na URL, e a assinatura é feita sobre
		// o `Host` que o servidor vai ver quando o portador usar a URL.
		String hostComPedido = header(request, "host");
		String hostBase = hostComPedido.endsWith(PREFIX)
			? hostComPedido.substring(0, hostComPedido.length() - PREFIX.length())
			: hostComPedido;
		// A assinatura usa o `Host` que o portador mandará ao usar a URL — sem o prefixo do gateway:
		// o `PREFIX` é da instalação, não do protocolo, e entra só na URL como caminho.
		String hostAssinado = hostBase;
		String path = "/" + bucket + "/" + key;

		// Os parâmetros da URL: datas e escopo, na forma que a AWS usa. `X-Amz-Credential` leva
		// `/`, codificado duas vezes porque é dado DENTRO de um parâmetro.
		Map<String, String> query = new LinkedHashMap<>();
		query.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
		query.put("X-Amz-Credential", credential.accessKey() + "/" + dateStamp + "/" + REGION + "/s3/aws4_request");
		query.put("X-Amz-Date", amzDate);
		query.put("X-Amz-Expires", String.valueOf(expires));
		query.put("X-Amz-SignedHeaders", "host");
		Map<String, String> headers = Map.of("host", hostAssinado, "x-amz-date", amzDate,
			"x-amz-content-sha256", "UNSIGNED-PAYLOAD");
		var assinado = new SigV4Verifier.Request(verbo, path, query, headers, "UNSIGNED-PAYLOAD");
		var parsed = new SigV4Verifier.Parsed(credential.accessKey(), REGION, "s3", "host", "");
		String signature = SigV4Verifier.signature(assinado, parsed, secret, dateStamp);

		StringBuilder url = new StringBuilder(request.getScheme()).append("://").append(hostBase).append(PREFIX)
			.append('/').append(SigV4Verifier.uriEncode(bucket).replace("%2F", "/"));
		for (String segment : key.split("/")) {
			url.append('/').append(SigV4Verifier.uriEncode(segment));
		}
		url.append("?X-Amz-Algorithm=AWS4-HMAC-SHA256")
			// O valor vai no texto em que ELE É ASSINADO: `X-Amz-Credential` com `/`, porque o texto de
			// URL é uma camada acima da assinatura. Escapar aqui (`%2F` ou de novo `%252F`) faz o gateway
			// ver um valor diferente do assinado — e a URL emitida não abre.
			.append("&X-Amz-Credential=").append(query.get("X-Amz-Credential"))
			.append("&X-Amz-Date=").append(amzDate)
			.append("&X-Amz-Expires=").append(expires)
			.append("&X-Amz-SignedHeaders=host")
			.append("&X-Amz-Signature=").append(signature);
		return Map.of("url", url.toString(), "method", verbo,
			"expiresIn", expires,
			"expiresAt", clock.instant().plusSeconds(expires).toString());
	}

	private S3Credential authenticate(HttpServletRequest request, String operation) {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		SigV4Verifier.Parsed parsed = SigV4Verifier.parseAuthorization(authorization);
		if (parsed == null) {
			// Sem cabeçalho: pode ser uma URL pré-assinada (`X-Amz-Signature` na query). É a única
			// forma de um navegador — que não tem o segredo — ler ou gravar um objeto.
			S3Credential presigned = authenticatePresigned(request, operation);
			if (presigned != null) {
				return presigned;
			}
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

	/** XML de erro no formato do S3 — o formato que o SDK interpreta. Todo valor passa pelo builder. */
	static String errorXml(String code, String message, String resource) {
		return new S3Xml().open("Error").element("Code", code).element("Message", message)
			.element("Resource", resource == null ? "" : resource)
			.element("RequestId", java.util.UUID.randomUUID().toString()).close("Error").toString();
	}
}