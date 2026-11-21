package com.socialcoisa.rede.network.adapters.in.web;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
import com.socialcoisa.rede.network.domain.ObjectMetadata;
import com.socialcoisa.rede.network.domain.S3Credential;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
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
 * <p><b>Multipart Upload (V365):</b> {@code POST /{bucket}/{key}?uploads} cria, {@code PUT
 * /{bucket}/{key}?partNumber=&uploadId=} recebe cada parte (fora de ordem, reenvio substitui) e
 * {@code POST /{bucket}/{key}?uploadId=} conclui montando o corpo na ordem pedida. A conclusão grava pelo
 * mesmo caminho do PUT simples: nada vira objeto antes de o arquivo inteiro existir. Detalhes em
 * {@code StorageMultipartService} e em {@code 18-storage-gateway-s3.md} §4.2.</p>
 *
 * <p><b>Não implementado:</b> CopyObject e UploadPartCopy são <b>recusados com 501</b> antes de
 * qualquer escrita — um PUT com {@code x-amz-copy-source} não grava objeto nem parte vazia. ACL,
 * tagging, {@code versionId}, bucket policy e {@code POST} de formulário não têm suporte e não são
 * anunciados; a doc 18 é a lista do que existe.</p>
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

	/** O cabeçalho do S3 para copiar no servidor. CopyObject e UploadPartCopy não existem aqui. */
	private static final String COPY_SOURCE = "x-amz-copy-source";

	/** O formato de instante da S3: sempre três casas decimais e sempre {@code Z}. */
	private static final DateTimeFormatter S3_INSTANT = DateTimeFormatter
		.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);

	/** O prefixo do metadado declarado pelo cliente no PUT; volta como cabeçalho no HEAD e no GET. */
	static final String META_PREFIX = "x-amz-meta-";

	private final S3CredentialRepository credentials;
	private final StorageCatalogRepository catalog;
	private final StorageCatalogService storage;
	private final StorageContentService content;
	private final com.socialcoisa.rede.network.application.StorageMultipartService multipart;
	private final Clock clock;
	private final com.socialcoisa.rede.shared.web.CurrentUserResolver currentUserResolver;
	/** Teto de assinatura errada por Access Key — a Access Key é pública, o segredo não. */
	private final com.socialcoisa.rede.shared.security.IpRateLimiter tentativas;

	S3GatewayController(S3CredentialRepository credentials, StorageCatalogRepository catalog,
			StorageCatalogService storage, StorageContentService content, Clock clock,
			com.socialcoisa.rede.shared.web.CurrentUserResolver currentUserResolver,
			com.socialcoisa.rede.network.application.StorageMultipartService multipart,
			com.socialcoisa.rede.shared.security.IpRateLimiter tentativas) {
		this.credentials = credentials;
		this.catalog = catalog;
		this.storage = storage;
		this.content = content;
		this.clock = clock;
		this.currentUserResolver = currentUserResolver;
		this.multipart = multipart;
		this.tentativas = tentativas;
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
		requireBucketDoDono(credential, bucket);
		Map<String, String> query = query(request);
		boolean v2 = "2".equals(query.get("list-type"));
		int maxKeys = parseMaxKeys(query.get("max-keys"));
		// `continuation-token` (v2) e `marker` (v1) são a mesma coisa: a chave da última página.
		String after = v2 ? query.get("continuation-token") : query.get("marker");
		String prefix = query.get("prefix");
		String delimiter = query.get("delimiter");
		// `delimiter` vazio é "sem agrupamento" na prática; o parâmetro do S3 é de um caractere.
		if (delimiter != null && delimiter.isEmpty()) {
			delimiter = null;
		}
		// Uma linha a mais do que a página: é ela que diz se há próxima. "Página cheia" e "há mais"
		// eram a mesma conta antes, e um bucket com exatamente `max-keys` objetos saía truncado com
		// token, mandando o cliente pedir uma página que não existia.
		//
		// Com `delimiter`, cada grupo de subpasta conta como UMA entrada no `MaxKeys` — é assim no
		// S3. Um grupo consome várias linhas do banco, então a leitura pede mais linhas quando há
		// agrupamento: pedir só `maxKeys + 1` faria uma página curta com `IsTruncated=false` mesmo
		// havendo objetos depois do último grupo lido.
		int busca = delimiter == null ? maxKeys + 1 : Math.min(StorageCatalogService.MAX_LIST_LIMIT, (maxKeys + 1) * 10);
		List<StorageObject> achados = storage.list(bucket, prefix, after, busca, true);
		boolean talvezMais = achados.size() > (delimiter == null ? maxKeys : busca - 1);

		List<StorageObject> chaves = new ArrayList<>();
		java.util.LinkedHashSet<String> grupos = new java.util.LinkedHashSet<>();
		int contados = 0;
		String ultimoEmitido = null;
		boolean truncated = false;
		for (StorageObject object : achados) {
			String grupo = delimiter == null ? null : grupoDe(object.key(), prefix, delimiter);
			// O grupo já emitido não gasta entrada: todas as chaves dele só confirmam o mesmo prefixo.
			if (grupo != null && grupos.contains(grupo)) {
				ultimoEmitido = grupo;
				continue;
			}
			if (contados >= maxKeys) {
				truncated = true;
				break;
			}
			contados++;
			ultimoEmitido = grupo == null ? object.key() : grupo;
			if (grupo == null) {
				chaves.add(object);
			}
			else {
				grupos.add(grupo);
			}
		}
		// Sem linha extra e já no teto de linhas lidas: pode haver mais depois.
		if (!truncated && chaves.size() + grupos.size() >= maxKeys && talvezMais) {
			truncated = true;
		}

		S3Xml xml = new S3Xml();
		xml.root("ListBucketResult").element("Name", bucket);
		if (prefix != null) {
			xml.element("Prefix", prefix);
		}
		if (delimiter != null) {
			xml.element("Delimiter", delimiter);
		}
		xml.element("MaxKeys", maxKeys).element("KeyCount", contados).element("IsTruncated", truncated);
		if (truncated && ultimoEmitido != null) {
			xml.element(v2 ? "NextContinuationToken" : "NextMarker", ultimoEmitido);
		}
		for (StorageObject object : chaves) {
			xml.open("Contents").element("Key", object.key())
				.element("LastModified", lastModified(object))
				.element("ETag", "\"" + (object.sha256() == null ? "" : object.sha256()) + "\"")
				.element("Size", object.bytes() == null ? 0 : object.bytes()).element("StorageClass", "STANDARD")
				.close("Contents");
		}
		for (String grupo : grupos) {
			xml.open("CommonPrefixes").element("Prefix", grupo).close("CommonPrefixes");
		}
		xml.close("ListBucketResult");
		return xml(HttpStatus.OK).body(xml.toString());
	}

	/**
	 * O prefixo comum de uma chave com {@code delimiter}: o trecho até a <b>primeira</b> ocorrência
	 * do delimitador depois do {@code prefix} pedido, inclusive.
	 *
	 * <p>Chave que não tem o delimitador depois do prefixo não forma grupo — ela é conteúdo do
	 * prefixo (é o caso de {@code fotos/raiz.jpg} pedindo {@code prefix=fotos/&delimiter=/}).</p>
	 */
	static String grupoDe(String key, String prefix, String delimiter) {
		int inicio = prefix == null ? 0 : prefix.length();
		if (key.length() <= inicio) {
			return null;
		}
		int achado = key.indexOf(delimiter, inicio);
		return achado < 0 ? null : key.substring(0, achado + delimiter.length());
	}

	/**
	 * DeleteBucket: {@code 204} quando o bucket não tem objeto vivo; {@code 409 BucketNotEmpty} quando
	 * tem, como o S3.
	 *
	 * <p><b>Antes:</b> respondia {@code 501 NotImplemented} em <b>todos</b> os casos, com a
	 * justificativa "não há bucket vazio a apagar". A premissa estava errada: bucket existe enquanto
	 * tem objeto <b>vivo</b> ({@code bucketsOf} filtra {@code state <> 'DELETED'}), então um bucket
	 * cujas chaves foram todas removidas continuava em {@code ListBuckets} e o cliente não tinha como
	 * tirá-lo. A rota também recusava com {@code 403} um bucket que só tinha lápides, porque a
	 * conferência de dono usa a mesma lista de vivos: o dono "perdia" o próprio bucket ao esvaziá-lo.</p>
	 *
	 * <p><b>Agora:</b> a donagem de um bucket <b>esvaziado</b> é provada pelo que sobrou no catálogo
	 * (as lápides), não só pelos vivos. Sem nada vivo e com lápides do dono → {@code 204}. Bucket
	 * que o dono nunca gravou continua {@code 403}: não é dele.</p>
	 */
	@DeleteMapping("/{bucket}")
	ResponseEntity<Void> deleteBucket(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "DELETE");
		requireBucket(credential, bucket);
		requireBucketDoDonoOuEsvaziado(credential, bucket);
		List<StorageObject> vivos = storage.list(bucket, null, null, 1);
		if (!vivos.isEmpty()) {
			throw new ApiException(409, "BucketNotEmpty", "O bucket ainda tem objetos.");
		}
		// O bucket já "não existe" para o dono: nada vivo, nada listado. O 204 é a confirmação do S3.
		return ResponseEntity.noContent().build();
	}

	/**
	 * Donagem para o {@code DELETE} do bucket: vale o bucket que o dono tem <b>hoje</b> ou o que ele
	 * <b>esvaziou</b>.
	 *
	 * <p>{@code bucketsOf} conta só objeto vivo — é a resposta certa para ler e gravar, mas errada
	 * para apagar: depois da última lápide o dono deixaria de ver o próprio bucket e receberia
	 * {@code 403} ao tentar removê-lo. O que prova a donagem aqui é a autoria do que está no
	 * catálogo, vivo ou como lápide.</p>
	 */
	private void requireBucketDoDonoOuEsvaziado(S3Credential credential, String bucket) {
		if (catalog.bucketHasObjectsOf(bucket, credential.ownerUserId())) {
			return;
		}
		throw s3Error(403, "AccessDenied", "Este bucket nao e do dono da credencial.");
	}

	// ---------------------------------------------------------------- objeto

	/**
	 * PutObject: o corpo entra pelo caminho do catálogo; a resposta devolve o ETag (o digest).
	 *
	 * <p><b>Duas fases, porque o Storage não tem tabela de buckets.</b> O nome do bucket nasce do
	 * primeiro objeto, então a donagem não pode ser conferida antes da gravação: um bucket novo ainda não
	 * tem dono, e recusar antes faria o primeiro objeto de <b>toda</b> conta falhar. A ordem é:</p>
	 * <ol>
	 *   <li>grava — a gravação é o que dá dono ao bucket;</li>
	 *   <li>pergunta — todo objeto vivo no bucket é do dono da credencial? Se um só for de outra conta, o
	 *   bucket é alheio: a gravação é desfeita e a resposta é 403.</li>
	 * </ol>
	 *
	 * <p>A desfeita nunca é lápide: {@code state = 'DELETED'} sai do {@code bucketsOf}, então apagar por
	 * cima do último objeto de uma conta faria o bucket deixar de ser dela. {@link #desfazer} remove o que
	 * esta gravação criou e devolve o objeto que ela substituiu ao estado em que estava.</p>
	 */
	@RequestMapping(value = "/{bucket}/{*key}", method = RequestMethod.PUT)
	ResponseEntity<Void> putObject(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "PUT");
		requireBucket(credential, bucket);
		recusarCopiaNoServidor(request);
		String key = keyOf(request);
		// O tipo é o que o SDK manda; sem ele, o mesmo padrão do resto do módulo.
		String contentType = request.getContentType();
		// Refazer um PUT na mesma chave é o caso normal do S3: `beginUpload` devolve o objeto que já
		// existe, que pode estar STORED. Guardar o estado de antes é o que permite devolvê-lo intacto
		// se a gravação for recusada por donagem.
		StorageObject antes = catalog.find(bucket, key).orElse(null);
		// Metadado e cabeçalhos declarados junto do corpo (V367): lidos ANTES da gravação, porque cabeçalho
		// recusado não pode deixar objeto criado — nem um PENDING decorado com o que não foi aceito.
		StorageCatalogService.ClientHeaders declarados = clientHeaders(request);
		StorageObject pending;
		try {
			pending = storage.beginUpload(credential.ownerUserId(), bucket, key,
				contentType == null ? "application/octet-stream" : contentType);
		}
		catch (ApiException recusado) {
			// A cota também recusa aqui, e no mesmo vocabulário do S3: o SDK lê `Code`, não o JSON
			// do Control Plane. A abertura traduzia pelo `catch` da gravação, que ela nunca alcança.
			throw noVocabularioDoS3(recusado);
		}
		storage.saveClientHeaders(pending, declarados);
		boolean iaCriar = antes == null || !antes.id().equals(pending.id());
		if (!iaCriar && antes.state() != StorageObjectState.PENDING) {
			throw s3Error(409, "ObjectAlreadyStored", "A chave ja tem copia confirmada; use outro nome de chave.");
		}
		// O catálogo mede o digest em pedaços de 64 KiB e grava; é o mesmo caminho da rota do nó.
		// A recusa por cota de participante é 507 <b>no formato do S3</b> com o código interno: o SDK
		// não conhece "STORAGE_OWNER_QUOTA_EXCEEDED", e um XML sem `Code` que ele entenda viraria
		// falha opaca do cliente em vez de "seu espaço acabou".
		StorageObject stored;
		try {
			stored = storage.finishUpload(pending.id(), body(request), credential.ownerUserId());
		}
		catch (ApiException recusado) {
			throw noVocabularioDoS3(recusado);
		}
		if (!bucketSemDono(bucket, credential.ownerUserId())) {
			desfazer(stored, antes);
			throw s3Error(403, "AccessDenied", "Este bucket nao e do dono da credencial.");
		}
		return ResponseEntity.ok()
			.eTag("\"" + stored.sha256() + "\"")
			.header(HttpHeaders.CONTENT_LENGTH, "0")
			.build();
	}

	/**
	 * Desfaz uma gravação recusada por donagem.
	 *
	 * <p>O que conta é <b>a linha desta gravação</b>, não {@code stored.id()}: quando a chave já existia,
	 * {@code finishUpload} devolve o <i>mesmo</i> id, e apagar a linha seria apagar o objeto do dono — o
	 * oposto do que a recusa quer. Por isso só o objeto <b>novo</b> ({@code iaCriar}) é removido; o que
	 * existia antes é devolvido ao estado anterior (o corpo já gravado deixa o catálogo e volta a ser
	 * PENDING, que é o estado de espera que ele realmente tem).</p>
	 */
	private void desfazer(StorageObject stored, StorageObject antes) {
		if (antes != null && antes.id().equals(stored.id())) {
			catalog.restorePending(stored.id());
			return;
		}
		catalog.discardPending(stored.id());
	}

	// ---------------------------------------------------------------- multipart upload (V365)

	/**
	 * CreateMultipartUpload: {@code POST /{bucket}/{key}?uploads}.
	 *
	 * <p>É o único ponto em que a donagem do bucket é provada por gravação: o multipart cria a chave
	 * como o PUT cria, então a conferência vem depois — se o bucket for de outra conta, o upload é
	 * descartado e a resposta é 403.</p>
	 */
	@RequestMapping(value = "/{bucket}/{*key}", method = RequestMethod.POST, params = "uploads")
	ResponseEntity<String> createMultipart(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "PUT");
		requireBucket(credential, bucket);
		String key = keyOf(request);
		String expires = query(request).get("expires");
		var upload = multipart.create(credential.ownerUserId(), credential.id(), bucket, key,
			request.getContentType(), expires == null ? null : Long.valueOf(expires))
			.comCabeçalhos(clientHeaders(request));
		if (!bucketSemDono(bucket, credential.ownerUserId())) {
			multipart.abort(upload.uploadId());
			throw s3Error(403, "AccessDenied", "Este bucket nao e do dono da credencial.");
		}
		S3Xml xml = new S3Xml();
		xml.root("InitiateMultipartUploadResult").element("Bucket", bucket).element("Key", key)
			.element("UploadId", upload.uploadId());
		return xml(HttpStatus.OK).body(xml.toString());
	}

	/**
	 * UploadPart: {@code PUT /{bucket}/{key}?partNumber=N&uploadId=...}.
	 *
	 * <p>A credencial é a do upload aberto (e do dono dela) — não uma assinatura nova. É o que impede
	 * que uma parte entre no upload por uma conta que não abriu nenhum.</p>
	 */
	@RequestMapping(value = "/{bucket}/{*key}", method = RequestMethod.PUT, params = {
			"partNumber", "uploadId" })
	ResponseEntity<Void> uploadPart(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "PUT");
		requireBucket(credential, bucket);
		recusarCopiaNoServidor(request);
		java.util.Map<String, String> query = query(request);
		var upload = uploadDaChave(credential, bucket, request);
		int numero;
		try {
			numero = Integer.parseInt(query.get("partNumber"));
		}
		catch (NumberFormatException notNumber) {
			throw s3Error(400, "InvalidArgument", "partNumber invalido.");
		}
		// O `Content-Length` é o único tamanho conhecido antes de ler: com ele a cota recusa a parte
		// sem gravar nada; sem ele (corpo em chunks), a conta fecha depois de a parte ser medida.
		String declarado = request.getHeader(HttpHeaders.CONTENT_LENGTH);
		Long anunciado = null;
		if (declarado != null && !declarado.isBlank()) {
			try {
				anunciado = Long.valueOf(declarado.trim());
			}
			catch (NumberFormatException invalido) {
				anunciado = null;
			}
		}
		com.socialcoisa.rede.network.application.StorageMultipartService.Part part;
		try {
			part = multipart.receivePart(upload, numero, body(request), anunciado);
		}
		catch (ApiException recusado) {
			throw noVocabularioDoS3(recusado);
		}
		return ResponseEntity.ok().eTag("\"" + part.sha256() + "\"").header(HttpHeaders.CONTENT_LENGTH, "0")
			.build();
	}

	/**
	 * CompleteMultipartUpload: {@code POST /{bucket}/{key}?uploadId=...} com a lista de partes.
	 *
	 * <p>A lista vem em XML ({@code <CompleteMultipartUpload><Part><PartNumber>..}). O corpo só é
	 * montado com as partes nomeadas; parte ausente é {@code InvalidPart} e o upload continua aberto.</p>
	 */
	@RequestMapping(value = "/{bucket}/{*key}", method = RequestMethod.POST, params = "uploadId")
	ResponseEntity<String> completeMultipart(@PathVariable String bucket, HttpServletRequest request)
			throws java.io.IOException {
		S3Credential credential = authenticate(request, "PUT");
		requireBucket(credential, bucket);
		var upload = uploadDaChave(credential, bucket, request);
		java.util.List<Integer> pedidas = partesDoXml(body(request));
		var concluido = multipart.complete(upload, pedidas);
		S3Xml xml = new S3Xml();
		xml.root("CompleteMultipartUploadResult").element("Location", bucket + "/" + concluido.key())
			.element("Bucket", bucket).element("Key", concluido.key()).element("ETag", "\"" + concluido.etag() + "\"");
		return xml(HttpStatus.OK).body(xml.toString());
	}

	/** AbortMultipartUpload: {@code DELETE /{bucket}/{key}?uploadId=...}. */
	@RequestMapping(value = "/{bucket}/{*key}", method = RequestMethod.DELETE, params = "uploadId")
	ResponseEntity<Void> abortMultipart(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "DELETE");
		requireBucket(credential, bucket);
		var upload = uploadDaChave(credential, bucket, request);
		multipart.abort(upload.uploadId());
		return ResponseEntity.noContent().build();
	}

	/**
	 * O upload do {@code uploadId}, <b>conferindo que é desta chave</b>.
	 *
	 * <p>O {@code uploadId} identifica o upload; a rota identifica o objeto. As duas coisas têm de
	 * coincidir, e a S3 responde {@code 404 NoSuchUpload} quando não coincidem — para a chave pedida,
	 * aquele upload não existe.</p>
	 *
	 * <p><b>Antes:</b> {@code UploadPart} e {@code CompleteMultipartUpload} conferiam; {@code ListParts}
	 * e {@code AbortMultipartUpload} não. O {@code ListParts} de uma chave errada devolvia as partes de
	 * <b>outro</b> arquivo com {@code 200}, e o {@code Abort} de uma chave errada <b>apagava</b> o upload
	 * — um bug de concatenação de chave no cliente destruía o trabalho de outro arquivo. Reunir a
	 * conferência num só ponto é o que impede a divergência de voltar pela terceira rota.</p>
	 */
	private com.socialcoisa.rede.network.application.StorageMultipartService.Upload uploadDaChave(S3Credential credential, String bucket,
			HttpServletRequest request) {
		var upload = multipart.require(query(request).get("uploadId"), credential.ownerUserId());
		if (!upload.bucket().equals(bucket) || !upload.key().equals(keyOf(request))) {
			throw s3Error(404, "NoSuchUpload", "O upload informado nao e desta chave.");
		}
		return upload;
	}

	/** ListParts: {@code GET /{bucket}/{key}?uploadId=...} — o cliente confere o que já chegou. */
	@RequestMapping(value = "/{bucket}/{*key}", method = RequestMethod.GET, params = "uploadId")
	ResponseEntity<String> listParts(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "LIST");
		requireBucket(credential, bucket);
		var upload = uploadDaChave(credential, bucket, request);
		S3Xml xml = new S3Xml();
		xml.root("ListPartsResult").element("Bucket", bucket).element("Key", upload.key())
			.element("UploadId", upload.uploadId());
		for (var part : multipart.parts(upload.uploadId())) {
			xml.open("Part").element("PartNumber", part.number()).element("ETag", "\"" + part.sha256() + "\"")
				.element("Size", part.bytes()).close("Part");
		}
		xml.close("ListPartsResult");
		return xml(HttpStatus.OK).body(xml.toString());
	}

	/** A lista de {@code <PartNumber>} do CompleteMultipartUpload, na ordem em que o cliente mandou. */
	private static java.util.List<Integer> partesDoXml(java.io.InputStream body) throws java.io.IOException {
		String texto = new String(body.readAllBytes(), StandardCharsets.UTF_8);
		java.util.List<Integer> numeros = new java.util.ArrayList<>();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("<PartNumber>\\s*(\\d+)\\s*</PartNumber>")
			.matcher(texto);
		while (m.find()) {
			numeros.add(Integer.parseInt(m.group(1)));
		}
		if (numeros.isEmpty()) {
			throw s3Error(400, "MalformedXML", "CompleteMultipartUpload sem partes.");
		}
		return numeros;
	}

	/**
	 * GetObject: Range/206/416, 304 por If-None-Match, ETag = digest.
	 *
	 * <p><b>Ordem deliberada:</b> donagem antes da existência. O S3 responde 404 a um bucket que o
	 * chamador não pode ver; aqui a resposta é 403. O motivo é que um 404 para quem tem credencial válida
	 * deixaria o chamador achar que o bucket é dele e o objeto sumiu — e o Storage não tem "bucket
	 * privado": ou é do dono, ou não existe para ele.</p>
	 */
	@GetMapping("/{bucket}/{*key}")
	ResponseEntity<StreamingResponseBody> getObject(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "GET");
		requireBucket(credential, bucket);
		requireBucketDoDono(credential, bucket);
		StorageObject object = comVocabularioDoS3(() -> storage.requireDownloadable(bucket, keyOf(request)));
		return comVocabularioDoS3(() -> content.respond(object, request.getHeader(HttpHeaders.RANGE),
			request.getHeader(HttpHeaders.IF_NONE_MATCH)));
	}

	/** HeadObject: mesmos cabeçalhos do GetObject, sem corpo. */
	@RequestMapping(value = "/{bucket}/{*key}", method = RequestMethod.HEAD)
	ResponseEntity<Void> headObject(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "GET");
		requireBucket(credential, bucket);
		requireBucketDoDono(credential, bucket);
		StorageObject object = comVocabularioDoS3(() -> storage.requireDownloadable(bucket, keyOf(request), "HEAD"));
		return comVocabularioDoS3(() -> content.head(object));
	}

	/** DeleteObject: lápide no catálogo. A resposta é 204 mesmo se a chave não existir (igual ao S3). */
	@DeleteMapping("/{bucket}/{*key}")
	ResponseEntity<Void> deleteObject(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "DELETE");
		requireBucket(credential, bucket);
		requireBucketDoDono(credential, bucket);
		String key = keyOf(request);
		Optional<StorageObject> object = catalog.find(bucket, key);
		if (object.isPresent()) {
			storage.delete(credential.ownerUserId(), bucket, key);
		}
		return ResponseEntity.noContent().build();
	}

	/**
	 * Lê {@code x-amz-meta-*}, {@code Cache-Control} e {@code Content-Disposition} do pedido.
	 *
	 * <p>O S3 trata o metadado como {@code x-amz-meta-<nome>} com o nome em minúsculas e sem o prefixo;
	 * é essa a forma que o HEAD devolve, e é o que o {@link ObjectMetadata} normaliza. O tipo de
	 * conteúdo é decidido pela coluna própria, então o {@code Content-Type} não entra aqui.</p>
	 */
	private static StorageCatalogService.ClientHeaders clientHeaders(HttpServletRequest request) {
		Map<String, String> meta = new LinkedHashMap<>();
		for (java.util.Enumeration<String> nomes = request.getHeaderNames(); nomes != null && nomes.hasMoreElements();) {
			String nome = nomes.nextElement();
			if (nome != null && nome.toLowerCase(Locale.ROOT).startsWith(META_PREFIX)) {
				// `putIfAbsent`: cabeçalho repetido vale a primeira ocorrência, como no próprio contrato.
				meta.putIfAbsent(nome.substring(META_PREFIX.length()), request.getHeader(nome));
			}
		}
		ObjectMetadata metadata;
		try {
			metadata = ObjectMetadata.of(meta);
		}
		catch (IllegalArgumentException invalido) {
			throw s3Error(400, "InvalidArgument", invalido.getMessage());
		}
		return StorageCatalogService.clientHeaders(request.getHeader("Cache-Control"),
			request.getHeader("Content-Disposition"), metadata);
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
		exigirOrcamento(presigned.parsed().accessKey());
		S3Credential credential = credentials.findByAccessKey(presigned.parsed().accessKey())
			.filter(c -> c.revokedAt() == null)
			.orElseThrow(() -> {
				contaFalha(presigned.parsed().accessKey());
				return s3Error(403, "InvalidAccessKeyId", "A credencial da URL nao existe ou foi revogada.");
			});
		String secret = credentials.secretFor(credential.id());
		if (secret == null) {
			throw s3Error(403, "SignatureDoesNotMatch",
				"Credencial sem segredo guardado nao pode assinar; gere uma nova.");
		}
		if (!SigV4Verifier.verifyPresigned(presigned, secret, clock.instant())) {
			contaFalha(presigned.parsed().accessKey());
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
		// Mesma conta da assinatura por cabeçalho: a URL é a credencial, e portador de link também
		// consome o Control Plane.
		exigirRitmo(credential.accessKey());
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
		if (!catalog.bucketsOf(user.id().value()).contains(bucket)) {
			throw s3Error(403, "AccessDenied", "Este bucket nao e do dono da credencial.");
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

	/**
	 * Sem orçamento, nem o segredo é buscado: a tentativa barrada é recusada antes do trabalho.
	 *
	 * <p>O teto é por Access Key, não por IP: a Access Key é o alvo do teste, e o atacante pode
	 * trocar de IP. Limitar só por IP deixaria o ataque passar distribuído — e barraria um escritório
	 * inteiro por causa de um vizinho.</p>
	 */
	private void exigirOrcamento(String accessKey) {
		if (accessKey != null && !accessKey.isBlank() && tentativas != null
				&& !tentativas.permiteTentarAutenticacao(com.socialcoisa.rede.shared.security.IpRateLimiter.S3_AUTH,
					accessKey)) {
			throw s3Error(429, "SlowDown",
				"Muitas assinaturas invalidas para esta credencial. Tente de novo mais tarde.");
		}
	}

	private void contaFalha(String accessKey) {
		if (accessKey != null && !accessKey.isBlank() && tentativas != null) {
			tentativas.registraFalhaDeAutenticacao(
				com.socialcoisa.rede.shared.security.IpRateLimiter.S3_AUTH, accessKey);
		}
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
		exigirOrcamento(parsed.accessKey());
		S3Credential credential = credentials.findByAccessKey(parsed.accessKey())
			.filter(c -> c.revokedAt() == null)
			.orElseThrow(() -> {
				contaFalha(parsed.accessKey());
				return s3Error(403, "InvalidAccessKeyId", "A credencial nao existe ou foi revogada.");
			});
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
			contaFalha(parsed.accessKey());
			throw s3Error(403, "SignatureDoesNotMatch", "A assinatura nao confere.");
		}
		if (!credential.allows(operation)) {
			throw s3Error(403, "AccessDenied", "A credencial nao permite " + operation + ".");
		}
		exigirRitmo(credential.accessKey());
		return credential;
	}

	/**
	 * Teto de requisição <b>válida</b> por credencial (brief §3.3, "controle de acesso").
	 *
	 * <p>Diferente de {@code exigirOrcamento}, que só soma assinatura que falhou: aqui entra a
	 * requisição de quem provou o segredo. O que se limita não é a segurança da credencial, é o quanto
	 * uma credencial consome do Control Plane — todo upload e download do gateway passa por este
	 * processo, e um cliente em laço (ou a credencial vazada) ocupa a instalação inteira. Como o
	 * balde é da Access Key, trocar de IP não zera a conta, que é o que um limitador por IP deixaria
	 * passar.</p>
	 */
	private void exigirRitmo(String accessKey) {
		if (accessKey != null && !accessKey.isBlank() && tentativas != null
				&& !tentativas.permiteRequisicao(com.socialcoisa.rede.shared.security.IpRateLimiter.S3_REQUEST,
					accessKey)) {
			throw s3Error(503, "SlowDown", "Muitas requisicoes para esta credencial. Tente de novo mais tarde.");
		}
	}

	/** O bucket pedido é o permitido pela credencial? {@code null} em allowedBucket = todos do dono. */
	/**
	 * O bucket está no <b>escopo da credencial</b>.
	 *
	 * <p>Esta é a metade barata da pergunta. A outra metade — o bucket é do <b>dono</b>? — não cabe
	 * aqui porque a resposta depende do verbo: um {@code PUT} numa chave nova <b>cria</b> o bucket, então
	 * conferir antes da gravação recusaria o primeiro objeto de toda conta. Quem pergunta a donagem é
	 * {@link #requireBucketDoDono}, chamado onde o dado já existe ou vai nascer.</p>
	 */
	private static void requireBucket(S3Credential credential, String bucket) {
		if (!credential.allowsBucket(bucket)) {
			throw s3Error(403, "AccessDenied", "A credencial nao permite este bucket.");
		}
	}

	/**
	 * O bucket pertence ao dono da credencial.
	 *
	 * <p>{@code allowedBucket} nulo significa "todos os buckets <b>do dono</b>", nunca "todos os buckets
	 * da rede". Sem esta conferência, uma credencial de escopo nulo — o padrão — lia, gravava e apagava
	 * em bucket de qualquer outra conta cuja chave conhecesse. O catálogo sempre soube o dono; o gateway
	 * é que não perguntava.</p>
	 *
	 * <p>O Storage não tem tabela de buckets: o nome nasce no primeiro upload, então "bucket do dono" é
	 * "o dono já gravou algo nele". <b>Ordem:</b> no {@code PUT} a conferência vem DEPOIS da gravação —
	 * é a gravação que cria o bucket do dono. Nos demais verbos, antes, com o dado já no catálogo.</p>
	 */
	/**
	 * O bucket pertence ao dono da credencial.
	 *
	 * <p>{@code allowedBucket} nulo significa "todos os buckets <b>do dono</b>", nunca "todos os buckets
	 * da rede". Sem esta conferência, uma credencial de escopo nulo — o padrão — lia, gravava e apagava
	 * em bucket de qualquer outra conta cuja chave conhecesse. O catálogo sempre soube o dono; o gateway
	 * é que não perguntava.</p>
	 */
	private void requireBucketDoDono(S3Credential credential, String bucket) {
		if (!catalog.bucketsOf(credential.ownerUserId()).contains(bucket)) {
			throw s3Error(403, "AccessDenied", "Este bucket nao e do dono da credencial.");
		}
	}

	/**
	 * O bucket ainda não pertence a ninguém — todo objeto vivo nele é desta credencial?
	 *
	 * <p>O Storage não tem tabela de buckets: o dono nasce do primeiro objeto. Um bucket cujos objetos
	 * vivos são todos desta credencial é, na prática, do dono — é o caso do primeiro upload. Basta um
	 * objeto de outra conta para o bucket ser alheio, e é isso que o {@code PUT} recusa.</p>
	 */
	private boolean bucketSemDono(String bucket, String ownerUserId) {
		// `list` devolve no máximo `limit` linhas: 1000 cobre a pergunta prática (o bucket já tem
		// objeto de outra conta nas primeiras chaves). Um bucket maior que isso com objeto alheio só no
		// fim passaria — por isso a pergunta é feita na GRAVAÇÃO, cujo objeto entra na lista.
		return catalog.list(bucket, null, null, 1000).stream()
			.allMatch(o -> ownerUserId.equals(o.createdBy()));
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

	/**
	 * Query decodificada (o SigV4 recodifica para canonicalizar).
	 *
	 * <p>Quando a query string não vem crua — `mockMvc(...).param(...)` monta os parâmetros sem query
	 * string, e um container também pode consumir parâmetros do corpo de um POST de formulário — a
	 * fonte é o mapa de parâmetros, que é o que o usuário vê servido de qualquer jeito. Ignorar essa
	 * forma fazia o multipart "não existir" por um uploadId que estava na requisição.</p>
	 */
	static Map<String, String> query(HttpServletRequest request) {
		Map<String, String> out = new LinkedHashMap<>();
		String query = request.getQueryString();
		if (query == null || query.isBlank()) {
			for (Map.Entry<String, String[]> parametro : request.getParameterMap().entrySet()) {
				String[] valores = parametro.getValue();
				if (valores != null && valores.length > 0) {
					out.put(parametro.getKey(), valores[0]);
				}
			}
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

	/**
	 * O {@code LastModified} da listagem, no formato que a S3 manda: {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}.
	 *
	 * <p><b>Antes:</b> saía {@code String.valueOf(createdAt)}, isto é {@code Instant.toString()} — que
	 * <b>omite</b> a fração quando ela é zero ({@code ...T08:00:00Z}) e emite até <b>nove</b> casas quando
	 * não é ({@code ...T08:00:00.123456789Z}). Os dois são instantes válidos em Java, e é por isso que
	 * passaram: o SDK da AWS desserializa o campo com um formatter de milissegundos e recusa o documento
	 * inteiro — a listagem que abre no navegador falha no cliente.</p>
	 *
	 * <p>O milissegundo é truncado, não arredondado: {@code 999999} ns não pode virar o segundo seguinte
	 * e dar a um objeto a data de outro.</p>
	 */
	static String lastModified(StorageObject object) {
		Instant quando = object.updatedAt() != null ? object.updatedAt() : object.createdAt();
		return S3_INSTANT.format(quando);
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

	/**
	 * CopyObject e UploadPartCopy chegam como um PUT com {@code x-amz-copy-source} e corpo vazio.
	 *
	 * <p><b>Antes:</b> o cabeçalho era ignorado e o PUT comum seguia até a gravação. Com o corpo
	 * vazio da cópia, a resposta era {@code 400 STORAGE_EMPTY_BODY} — como se o cliente tivesse
	 * mandado um arquivo vazio — e a abertura deixava uma linha {@code PENDING} no catálogo. Com
	 * corpo, os bytes do cliente eram gravados no destino e a resposta dizia 200: uma "cópia" que
	 * não copiou nada.</p>
	 *
	 * <p>Operação ausente responde {@code 501 NotImplemented} no XML do S3, antes de
	 * {@code beginUpload}/{@code receivePart}: nada é escrito — nem objeto, nem parte, nem linha.</p>
	 */
	private static void recusarCopiaNoServidor(HttpServletRequest request) {
		String origem = request.getHeader(COPY_SOURCE);
		if (origem != null && !origem.isBlank()) {
			throw s3Error(501, "NotImplemented",
				"CopyObject e UploadPartCopy nao sao suportados; envie os bytes no corpo do PUT.");
		}
	}

	/**
	 * A recusa do catálogo no <b>vocabulário do S3</b>.
	 *
	 * <p>O SDK não conhece {@code STORAGE_OWNER_QUOTA_EXCEEDED}: a cota tem de chegar como
	 * {@code 507 QuotaExceeded}, no XML de erro do protocolo. Sem esta tradução, a recusa saía como o
	 * JSON do Control Plane — {@code 507} que o cliente podia ler, mas não pelo código que ele trata.</p>
	 *
	 * <p>Um ponto só, para as rotas que gravam não divergirem: o {@code PUT} simples traduzia, o
	 * {@code UploadPart} não.</p>
	 */
	/**
	 * A recusa de uma rota de leitura traduzida do vocabulário do control plane para o do S3.
	 *
	 * <p>O objeto confirmado sem cópia registrada é um estado do catálogo que o cliente do S3 não
	 * conhece: a leitura respondia o JSON interno {@code STORAGE_PLACEMENT_MISSING}, e o SDK, que lê
	 * o {@code Code} do XML, caía numa falha opaca. A tradução do {@code PUT} não alcançava o
	 * {@code GET} nem o {@code HEAD}, porque a recusa nasce dentro do serviço de conteúdo.</p>
	 */
	static <T> T comVocabularioDoS3(java.util.function.Supplier<T> leitura) {
		try {
			return leitura.get();
		}
		catch (ApiException recusado) {
			throw noVocabularioDoS3(recusado);
		}
	}

	static ApiException noVocabularioDoS3(ApiException recusado) {
		if (recusado.status() == 507 && "STORAGE_OWNER_QUOTA_EXCEEDED".equals(recusado.code())) {
			return s3Error(507, "QuotaExceeded", recusado.getMessage());
		}
		if ("STORAGE_PLACEMENT_MISSING".equals(recusado.code())) {
			// O catálogo diz que existe e não há cópia: para o cliente do S3 é um conflito
			// transitório do lado do servidor, não um erro de pedido — e ele sabe tentar de novo.
			return s3Error(409, "InternalError", "O objeto esta confirmado no catalogo mas sem copia"
				+ " registrada em no nenhum; tente de novo.");
		}
		return recusado;
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