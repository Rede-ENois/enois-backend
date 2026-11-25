package com.socialcoisa.rede.network.adapters.in.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
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
import java.util.Set;

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
import com.socialcoisa.rede.network.domain.StorageBucketPolicy;
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
 * <tr><td>CopyObject</td><td>{@code PUT /{bucket}/{key}} + {@code x-amz-copy-source}</td><td>copia no servidor, sem trafegar os bytes</td></tr>
 * <tr><td>UploadPartCopy</td><td>{@code PUT /{bucket}/{key}?partNumber=&uploadId=} + {@code x-amz-copy-source}</td><td>parte a partir de um intervalo da origem</td></tr>
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
 * <p><b>Não implementado:</b> ACL, tagging, {@code versionId}, bucket policy e {@code POST} de
 * formulário não têm suporte e não são anunciados; a doc 18 é a lista do que existe.</p>
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

	/** O cabeçalho do S3 para copiar no servidor: CopyObject e UploadPartCopy existem. */
	private static final String COPY_SOURCE = "x-amz-copy-source";

	/**
	 * Subrecursos do objeto que o S3 implementa com um {@code PUT} e que este gateway <b>não</b>: a
	 * etiqueta, a ACL, o bloqueio de objeto e a retenção. Estão juntos porque a consequência de não
	 * recusá-los é a mesma — o XML da operação viraria o conteúdo do objeto.
	 */
	/**
	 * Subrecursos do PUT que gravaria o XML deles no corpo do objeto. Lista e não {@code Set}: a
	 * mensagem cita <b>um</b> subrecurso, e com dois na mesma requisição ({@code ?tagging&acl}) a ordem
	 * de um {@code Set.of} não é contrato — a mesma requisição responderia uma coisa ou outra.
	 */
	/** O teto do `DeleteObjects` do S3: mais de mil chaves num POST é recusa do protocolo. */
	private static final int MAX_DELETE_OBJECTS = 1000;

	private static final List<String> SUBRECURSOS_QUE_GRAVARIAM = List.of("tagging", "acl", "retention",
		"legal-hold", "object-lock");

	/**
	 * Subrecursos do {@code DELETE} de objeto que <b>apagariam a chave</b> sem fazer o que o cliente
	 * pediu.
	 *
	 * <p>{@code versionId} é a versão específica; os outros são as operações que o gateway não
	 * endereça e que o {@code PUT} já recusa (§4.21). Sem a guarda, o {@code DELETE} despachava pela
	 * rota e ignorava a consulta: quem pedia "apague as tags" ou "apague a versão 1" perdia o objeto
	 * inteiro — o mesmo defeito que o {@code DELETE} do bucket tinha com {@code ?cors} (§4.38).</p>
	 */
	private static final List<String> SUBRECURSOS_NAO_APAGAVEIS = List.of("versionId", "tagging", "acl",
		"retention", "legal-hold", "object-lock");

	/**
	 * Subrecursos de <b>leitura</b> do objeto que este gateway nao endereca.
	 *
	 * <p>{@code torrent} e {@code partNumber} so fazem sentido junto de operacoes que aqui nao
	 * existem. Sem a recusa, os dois serviam o corpo do arquivo — e a URL pre-assinada de
	 * {@code ?torrent} baixava o objeto.</p>
	 *
	 * <p>{@code versionId} <b>saiu</b> desta lista no §4.67: desde o versionamento a versao antiga
	 * existe, esta {@code STORED} e e servivel, e o {@code GET}/{@code HEAD} a serve.</p>
	 *
	 * <p>{@code response-content-*} fica de fora: entra na assinatura da URL pre-assinada (§4.28),
	 * entao adulterar e recusado, mas o gateway <b>nao</b> aplica o cabecalho (o
	 * {@code Content-Disposition} e o declarado no {@code PUT}).</p>
	 */
	private static final List<String> SUBRECURSOS_DE_LEITURA_NAO_ENDERECADOS = List.of("torrent", "partNumber");

	/**
	 * Subrecurso de <b>bucket</b> que e politica no S3 e aqui nao existe (a unica que age e a do
	 * varredor, {@code ?lifecycle}). Recusados no {@code DELETE}, onde sem a guarda viravam
	 * {@code DeleteBucket} — e num bucket vazio apagavam o bucket.
	 */
	private static final List<String> SUBRECURSOS_DE_BUCKET_NAO_IMPLEMENTADOS = List.of("cors", "tagging",
		"policy", "website", "notification", "logging", "replication", "encryption", "accelerate",
		"requestPayment", "publicAccessBlock", "ownershipControls", "analytics", "intelligent-tiering",
		"metrics", "inventory", "object-lock");

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

	/**
	 * GetBucketLifecycleConfiguration: {@code GET /{bucket}?lifecycle}.
	 *
	 * <p><b>Antes:</b> caía no {@code ListObjects} e respondia {@code 200 <ListBucketResult>} — o
	 * cliente perguntava o ciclo de vida e recebia a lista de objetos.</p>
	 *
	 * <p><b>Agora:</b> devolve as <b>mesmas</b> regras que o varredor aplica: {@code <Expiration>} vem
	 * do {@code expire_after_days} e {@code <NoncurrentVersionExpiration>} do
	 * {@code retain_superseded_days} da política (V352). Sem política, {@code
	 * <LifecycleConfiguration/>}.</p>
	 */
	@GetMapping(value = "/{bucket}", params = "lifecycle")
	ResponseEntity<String> getBucketLifecycle(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "LIST");
		requireBucket(credential, bucket);
		requireBucketDoDono(credential, bucket);
		StorageBucketPolicy politica = storage.bucketPolicy(bucket);
		S3Xml xml = new S3Xml();
		xml.root("LifecycleConfiguration");
		if (politica.expireAfterDays() != null || politica.retainSupersededDays() != null) {
			xml.open("Rule").element("ID", "enois-padrao").element("Status", "Enabled")
				.open("Filter").element("Prefix", "").close("Filter");
			if (politica.expireAfterDays() != null) {
				xml.open("Expiration").element("Days", String.valueOf(politica.expireAfterDays()))
					.close("Expiration");
			}
			if (politica.retainSupersededDays() != null) {
				xml.open("NoncurrentVersionExpiration")
					.element("NoncurrentDays", String.valueOf(politica.retainSupersededDays()))
					.close("NoncurrentVersionExpiration");
			}
			xml.close("Rule");
		}
		return xml(HttpStatus.OK).body(xml.toString());
	}

	/**
	 * ListMultipartUploads: {@code GET /{bucket}?uploads} — o que ficou aberto no bucket.
	 *
	 * <p><b>Antes:</b> a rota caía no {@code ListObjects}, que ignora o subrecurso e responde um
	 * {@code <ListBucketResult>} com os objetos. O cliente que pede os multiparts recebia uma lista de
	 * objetos <b>silenciosamente errada</b> — `aws s3api list-multipart-uploads` e o `rclone` que
	 * procuram upload órfão não achavam nada, e as partes abandonadas seguiam ocupando disco até a
	 * varredura de lixo.</p>
	 *
	 * <p><b>Agora:</b> {@code 200} com {@code <Upload><Key>…</Key><UploadId>…</UploadId><Initiated>…}
	 * por upload em aberto <b>do dono da credencial</b>; {@code prefix} filtra. Upload de outra conta
	 * não aparece — é omitido, não vira erro.</p>
	 */
	@GetMapping(value = "/{bucket}", params = "uploads")
	ResponseEntity<String> listMultipartUploads(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "LIST");
		requireBucket(credential, bucket);
		requireBucketDoDono(credential, bucket);
		S3Xml xml = new S3Xml();
		xml.root("ListMultipartUploadsResult").element("Bucket", bucket)
			.element("IsTruncated", "false");
		for (var upload : multipart.abertos(bucket, credential.ownerUserId(), query(request).get("prefix"))) {
			xml.open("Upload")
				.element("Key", upload.key())
				.element("UploadId", upload.uploadId())
				.element("Initiated", S3_INSTANT.format(upload.createdAt()))
				.close("Upload");
		}
		return xml(HttpStatus.OK).body(xml.toString());
	}

	/**
	 * ListObjectVersions: {@code GET /{bucket}?versions} — o histórico das chaves do dono.
	 *
	 * <p><b>Antes:</b> a rota caía no {@code ListObjects} e respondia um {@code <ListBucketResult>}
	 * com os objetos correntes e {@code 200}. O {@code aws s3api list-object-versions} mostrava cada
	 * chave uma vez só, e o operador não tinha como ver as versões substituídas ocupando disco.</p>
	 *
	 * <p><b>Agora:</b> {@code 200 <ListVersionsResult>} com um {@code <Version>} por versão e um
	 * {@code <DeleteMarker>} para a lápide — chave, {@code VersionId}, {@code IsLatest}, tamanho,
	 * ETag e data. Só do dono da credencial; {@code prefix} filtra.</p>
	 */
	@GetMapping(value = "/{bucket}", params = "versions")
	ResponseEntity<String> listObjectVersions(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "LIST");
		requireBucket(credential, bucket);
		requireBucketDoDono(credential, bucket);
		S3Xml xml = new S3Xml();
		xml.root("ListVersionsResult").element("Name", bucket)
			.element("IsTruncated", "false")
			.element("MaxKeys", "1000");
		for (StorageObject objeto : storage.versionsNoBucket(bucket, query(request).get("prefix"))) {
			if (!credential.ownerUserId().equals(objeto.createdBy())) {
				continue;
			}
			// Lápide não tem tamanho nem ETag: vira <DeleteMarker>, como no S3.
			boolean lapide = objeto.state() == StorageObjectState.DELETED;
			String tag = lapide ? "DeleteMarker" : "Version";
			xml.open(tag)
				.element("Key", objeto.key())
				.element("VersionId", String.valueOf(objeto.version()))
				.element("IsLatest", String.valueOf(objeto.currentVersion()))
				.element("LastModified", S3_INSTANT.format(objeto.createdAt()))
				.element("ETag", "\"" + objeto.sha256() + "\"")
				.element("Size", String.valueOf(objeto.bytes() == null ? 0 : objeto.bytes()));
			xml.close(tag);
		}
		return xml(HttpStatus.OK).body(xml.toString());
	}

	/**
	 * PutBucketLifecycleConfiguration: {@code PUT /{bucket}?lifecycle}.
	 *
	 * <p><b>Antes:</b> caía no {@code PutObject} e respondia
	 * {@code 400 InvalidRequest "Informe o bucket e a chave"}. O operador que tentava ajustar o prazo
	 * de expiração pelo protocolo S3 <b>não conseguia</b> — e se tivesse uma rota que respondesse
	 * sucesso, acreditaria ter mudado um prazo que o varredor segue aplicando do jeito antigo.</p>
	 *
	 * <p><b>Agora:</b> {@code <Expiration><Days>} grava em {@code expire_after_days} e
	 * {@code <NoncurrentVersionExpiration><NoncurrentDays>} em {@code retain_superseded_days} — a
	 * mesma linha que o varredor lê. O versionamento é preservado. Regra que este gateway não honra
	 * ({@code <Transition>}, {@code <AbortIncompleteMultipartUpload>}, {@code <Date>}) é
	 * {@code 400 InvalidRequest}: aceitar calado é o defeito que este ciclo fecha.</p>
	 */
	@RequestMapping(value = "/{bucket}", method = RequestMethod.PUT, params = "lifecycle")
	ResponseEntity<Void> putBucketLifecycle(@PathVariable String bucket, HttpServletRequest request)
			throws IOException {
		S3Credential credential = authenticate(request, "PUT");
		requireBucket(credential, bucket);
		requireBucketDoDono(credential, bucket);
		String texto = new String(body(request).readAllBytes(), StandardCharsets.UTF_8);
		for (String naoHonrada : List.of("Transition", "AbortIncompleteMultipartUpload", "NoncurrentVersionTransition")) {
			if (texto.contains("<" + naoHonrada + ">")) {
				throw s3Error(400, "InvalidRequest",
					"Este gateway nao aplica <" + naoHonrada + ">; a politica nao foi alterada.");
			}
		}
		// Por varredura do elemento, não por string literal: qualquer indentação (`\t`, 6 espaços,
		// `\r\n`) escapava da comparação byte-exata, `diasDoXml` voltava `null` e o `UPSERT` gravava
		// `null` por cima do prazo que o varredor segue aplicando — apagar em silêncio.
		for (String bloco : List.of("Expiration", "NoncurrentVersionExpiration", "NoncurrentVersionTransition")) {
			for (String trecho : trechosDe(texto, bloco)) {
				boolean temData = trecho.contains("<Date>");
				boolean temDias = trecho.contains("<Days>") || trecho.contains("<NoncurrentDays>");
				if (temData || !temDias) {
					throw s3Error(400, "InvalidRequest",
						"Prazo em <" + bloco + "> sem <Days> nao e suportado; a politica nao foi alterada.");
				}
			}
		}
		Integer dias = diasDoXml(texto, "Expiration", "Days");
		Integer diasNaoCorrente = diasDoXml(texto, "NoncurrentVersionExpiration", "NoncurrentDays");
		if (dias == null && diasNaoCorrente == null && !texto.contains("<Rule>")) {
			throw s3Error(400, "MalformedXML", "LifecycleConfiguration sem <Rule>.");
		}
		StorageBucketPolicy atual = storage.bucketPolicy(bucket);
		storage.putBucketPolicy(credential.ownerUserId(), bucket, atual.versioning(),
			diasNaoCorrente, dias);
		return ResponseEntity.ok().build();
	}

	/**
	 * GetBucketVersioning: {@code GET /{bucket}?versioning}.
	 *
	 * <p><b>Antes:</b> caía no {@code ListObjects} e respondia um {@code 200 <ListBucketResult>} com
	 * os objetos — documento do tipo errado para quem perguntou o versionamento.</p>
	 *
	 * <p><b>Agora:</b> {@code <VersioningConfiguration>} com {@code <Status>Enabled</Status>} ou
	 * {@code <Status>Suspended</Status>}; bucket nunca configurado sai com o elemento vazio, que é o
	 * "nunca ligado" do S3. {@code Suspended} não apaga o histórico: as versões antigas seguem
	 * listáveis e restauráveis.</p>
	 */
	@GetMapping(value = "/{bucket}", params = "versioning")
	ResponseEntity<String> getBucketVersioning(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "LIST");
		requireBucket(credential, bucket);
		requireBucketDoDono(credential, bucket);
		StorageBucketPolicy politica = storage.bucketPolicy(bucket);
		S3Xml xml = new S3Xml();
		xml.root("VersioningConfiguration");
		// `updatedBy` nulo é "nunca houve política": o S3 responde o elemento vazio, não Suspended.
		if (politica.updatedBy() != null) {
			xml.element("Status", politica.versioning() ? "Enabled" : "Suspended");
		}
		return xml(HttpStatus.OK).body(xml.toString());
	}

	/**
	 * PutBucketVersioning: {@code PUT /{bucket}?versioning}.
	 *
	 * <p><b>Antes:</b> caía no {@code PutObject} e respondia
	 * {@code 400 InvalidRequest "Informe o bucket e a chave"} — não havia jeito de ligar o
	 * versionamento pelo protocolo S3, embora o Storage o tenha desde o V352.</p>
	 *
	 * <p><b>Agora:</b> {@code Enabled} liga e {@code Suspended} desliga; as outras regras da política
	 * (retenção de versão substituída, expiração) são <b>preservadas</b> — este chamado só mexe no
	 * versionamento. Valor que não é nenhum dos dois é {@code 400 MalformedXML}.</p>
	 */
	@RequestMapping(value = "/{bucket}", method = RequestMethod.PUT, params = "versioning")
	ResponseEntity<Void> putBucketVersioning(@PathVariable String bucket, HttpServletRequest request)
			throws IOException {
		S3Credential credential = authenticate(request, "PUT");
		requireBucket(credential, bucket);
		requireBucketDoDono(credential, bucket);
		String texto = new String(body(request).readAllBytes(), StandardCharsets.UTF_8);
		// Recorte linear do <VersioningConfiguration>, não regex sobre o corpo: `.*` casava o ÚLTIMO
		// <Status> do documento (um `<Rule><Status>` depois da configuração), e a regex sobre o corpo
		// do chamador é o ReDoS que o CodeQL marca. A abertura pode carregar `xmlns`.
		int abreConfig = texto.indexOf("<VersioningConfiguration");
		String configuracao = null;
		if (abreConfig >= 0) {
			int fimConfig = texto.indexOf("</VersioningConfiguration>", abreConfig);
			if (fimConfig >= 0) {
				configuracao = texto.substring(texto.indexOf(">", abreConfig) + 1, fimConfig);
			}
		}
		String status = configuracao == null ? null : entre(configuracao, "<Status>", "</Status>");
		if (!"Enabled".equals(status) && !"Suspended".equals(status)) {
			throw s3Error(400, "MalformedXML",
				"O <Status> do versionamento tem de ser Enabled ou Suspended.");
		}
		StorageBucketPolicy atual = storage.bucketPolicy(bucket);
		boolean ligado = "Enabled".equals(status);
		// A retenção de versão substituída só vale com o versionamento ligado (regra do serviço).
		// Ele já recusa a combinação inconsistente; aqui a retenção é zerada ao suspender.
		storage.putBucketPolicy(credential.ownerUserId(), bucket, ligado,
			ligado ? atual.retainSupersededDays() : null, atual.expireAfterDays());
		return ResponseEntity.ok().build();
	}

	/**
	 * GetBucketAcl: {@code GET /{bucket}?acl}.
	 *
	 * <p><b>Antes:</b> caía no {@code ListObjects} e respondia {@code 200 <ListBucketResult>}. O
	 * {@code aws s3api get-bucket-acl} lia uma lista de arquivos como se fosse a ACL do bucket.</p>
	 *
	 * <p><b>Agora:</b> {@code <AccessControlPolicy>} com o dono da credencial e um grant
	 * {@code FULL_CONTROL} para ele. É a verdade do Storage: um bucket tem <b>um</b> dono, sem ACL por
	 * usuário — expor isso é mais honesto que inventar grantees.</p>
	 */
	@GetMapping(value = "/{bucket}", params = "acl")
	ResponseEntity<String> getBucketAcl(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "LIST");
		requireBucket(credential, bucket);
		requireBucketDoDono(credential, bucket);
		S3Xml xml = new S3Xml();
		xml.root("AccessControlPolicy").open("Owner")
			.element("ID", credential.ownerUserId())
			.element("DisplayName", credential.ownerUserId())
			.close("Owner")
			.open("AccessControlList").open("Grant")
			.open("Grantee").attribute("xsi", "http://www.w3.org/2001/XMLSchema-instance", "type", "CanonicalUser")
			.element("ID", credential.ownerUserId())
			.element("DisplayName", credential.ownerUserId())
			.close("Grantee")
			.element("Permission", "FULL_CONTROL")
			.close("Grant").close("AccessControlList");
		return xml(HttpStatus.OK).body(xml.toString());
	}

	/**
	 * GetBucketPolicy: {@code GET /{bucket}?policy}.
	 *
	 * <p><b>Antes:</b> caía no {@code ListObjects} e respondia {@code 200 <ListBucketResult>} — o
	 * {@code get-bucket-policy} lia objetos como política.</p>
	 *
	 * <p><b>Agora:</b> {@code 404 NoSuchBucketPolicy}, o "não há política" do S3. O gateway não aceita
	 * política de recurso: quem manda é a donagem (§4 do brief), e devolver um documento vazio ou
	 * inventado seria pior que a ausência declarada.</p>
	 */
	@GetMapping(value = "/{bucket}", params = "policy")
	ResponseEntity<Void> getBucketPolicy(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "LIST");
		requireBucket(credential, bucket);
		requireBucketDoDono(credential, bucket);
		throw s3Error(404, "NoSuchBucketPolicy",
			"Este bucket nao tem politica de recurso; o acesso e do dono da credencial.");
	}

	/** O inteiro de {@code <Bloco><Campo>N</Campo></Bloco>} no XML, ou {@code null} se ausente. */
	private static Integer diasDoXml(String texto, String bloco, String campo) {
		for (String trecho : trechosDe(texto, bloco)) {
			String valor = entre(trecho, "<" + campo + ">", "</" + campo + ">");
			if (valor != null) {
				try {
					return Integer.parseInt(valor.trim());
				}
				catch (NumberFormatException naoENumero) {
					return null;
				}
			}
		}
		return null;
	}

	/**
	 * Os trechos de cada {@code <Bloco>…</Bloco>} do XML, na ordem.
	 *
	 * <p>Varredura linear, como o parser do `CompleteMultipartUpload`: a versão anterior era uma
	 * expressão com {@code .*?} dos dois lados sobre o corpo inteiro, que o CodeQL marca e que erra
	 * em qualquer formatação que não a esperada.</p>
	 */
	private static List<String> trechosDe(String texto, String bloco) {
		List<String> trechos = new java.util.ArrayList<>();
		int cursor = 0;
		while (true) {
			int abre = texto.indexOf("<" + bloco + ">", cursor);
			if (abre < 0) {
				break;
			}
			int fecha = texto.indexOf("</" + bloco + ">", abre);
			if (fecha < 0) {
				break;
			}
			trechos.add(texto.substring(abre + bloco.length() + 2, fecha));
			cursor = fecha + bloco.length() + 3;
		}
		return trechos;
	}

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
		// `start-after` (só no V2) é a chave de partida de quem começa a varredura no meio; era
		// ignorado, e o cliente que retomava a partir de uma chave relistava tudo o que já tinha.
		// Quando há `continuation-token`, ele manda: o token já diz onde continuar, e o S3 ignora o
		// `start-after` nesse caso.
		// O token é opaco e assinado com o segredo da credencial e o bucket (§4.53): só vale o que este
		// gateway emitiu para este bucket. Lixo (ou token de outro bucket) é `400`, não "acabou".
		String after = v2
			? (query.get("continuation-token") != null
				? tokenDeContinuacao(credential, bucket, query.get("continuation-token")) : query.get("start-after"))
			: query.get("marker");
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
			xml.element(v2 ? "NextContinuationToken" : "NextMarker",
				v2 ? emitirTokenDeContinuacao(credential, bucket, ultimoEmitido) : ultimoEmitido);
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
		// `DELETE ?cors`, `?tagging`, `?policy` e apagar a politica daquele subrecurso, nao o bucket.
		recusarSubrecursoNoBucket(request, bucket);
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
	 * DeleteObjects: {@code POST /{bucket}?delete} apaga até 1000 chaves num único pedido.
	 *
	 * <p><b>Antes:</b> a rota não existia — `aws s3 rm --recursive` e o `rclone delete` mandam um POST
	 * com o XML das chaves e o gateway respondia {@code 500 INTERNAL_ERROR} (nenhum handler para o
	 * verbo naquele caminho). A limpeza em massa não tinha como acontecer: pagar uma requisição por
	 * chave num bucket grande é o caminho que o protocolo evita de propósito.</p>
	 *
	 * <p><b>Agora:</b> cada {@code <Object><Key>} vira uma lápide pelo mesmo {@code delete} do
	 * {@code DELETE}; chave inexistente sai como {@code <Deleted>} (apagar o que não há é sucesso no
	 * S3); chave de outra conta sai em {@code <Error>} com {@code AccessDenied} e não é tocada;
	 * {@code <Quiet>true</Quiet>} silencia os {@code <Deleted>}. Mais de 1000 chaves, ou nenhuma, é
	 * {@code 400 MalformedXML}.</p>
	 */
	@RequestMapping(value = "/{bucket}", method = RequestMethod.POST, params = "delete")
	ResponseEntity<String> deleteObjects(@PathVariable String bucket, HttpServletRequest request)
			throws IOException {
		S3Credential credential = authenticate(request, "DELETE");
		requireBucket(credential, bucket);
		// A mesma guarda do DELETE de uma chave: num bucket de outra conta, nada é apagado — nem as
		// chaves que a credencial criou lá antes de o bucket mudar de dono.
		requireBucketDoDonoOuEsvaziado(credential, bucket);
		String texto = new String(body(request).readAllBytes(), StandardCharsets.UTF_8);
		String quietoXml = entre(texto, "<Quiet>", "</Quiet>");
		boolean quieto = quietoXml != null && "true".equalsIgnoreCase(quietoXml.trim());
		// Varredura linear: `<Object>(.*?)</Object>` sobre o corpo do chamador é o ReDoS que o CodeQL
		// marca, e o recorte por `indexOf` é O(n) por construção.
		java.util.List<String> chaves = new java.util.ArrayList<>();
		for (String objeto : trechosDe(texto, "Object")) {
			String chave = entre(objeto, "<Key>", "</Key>");
			if (chave != null) {
				chaves.add(decodeXml(chave));
			}
		}
		if (chaves.isEmpty()) {
			throw s3Error(400, "MalformedXML", "DeleteObjects sem objetos.");
		}
		if (chaves.size() > MAX_DELETE_OBJECTS) {
			throw s3Error(400, "MalformedXML",
				"DeleteObjects aceita no maximo " + MAX_DELETE_OBJECTS + " chaves por pedido.");
		}
		S3Xml xml = new S3Xml();
		xml.root("DeleteResult");
		for (String chave : chaves) {
			try {
				// A mesma régua do DELETE de uma chave: só o objeto do dono da credencial sai.
				StorageObject objeto_ = comVocabularioDoS3(() -> storage.requireDownloadable(bucket, chave, "DELETE"));
				if (!credential.ownerUserId().equals(objeto_.createdBy())) {
					throw s3Error(403, "AccessDenied", "O objeto nao e do dono da credencial.");
				}
				storage.delete(credential.ownerUserId(), bucket, chave);
				if (!quieto) {
					xml.open("Deleted").element("Key", chave).close("Deleted");
				}
			}
			catch (ApiException recusado) {
				if (recusado.status() == 404) {
					// Idempotência: apagar o que já não existe é sucesso, e o S3 responde assim.
					if (!quieto) {
						xml.open("Deleted").element("Key", chave).close("Deleted");
					}
					continue;
				}
				xml.open("Error").element("Key", chave).element("Code", "AccessDenied")
					.element("Message", "O objeto nao pode ser apagado por esta credencial.").close("Error");
			}
		}
		xml.close("DeleteResult");
		return xml(HttpStatus.OK).body(xml.toString());
	}

	/** Desfaz o escape XML dos nomes de chave que vieram no corpo (o SDK os escapa). */
	private static String decodeXml(String valor) {
		return valor.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
			.replace("&apos;", "'").replace("&amp;", "&");
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

	/**
	 * Recusa, antes de abrir o objeto, o subrecurso de leitura que nao e {@code GetObject}.
	 *
	 * <p>O {@code GET} do objeto nao olhava a consulta: {@code ?versionId=} pedia uma versao e
	 * recebia a corrente com {@code 200} — o SDK que acabou de listar versoes lia outro conteudo
	 * como sucesso. {@code ?torrent} e {@code ?partNumber} so tem sentido com operacoes que aqui
	 * nao existem.</p>
	 */
	private static void recusarLeituraDeSubrecurso(HttpServletRequest request, String chaveDoObjeto) {
		for (String naoImplementado : SUBRECURSOS_DE_LEITURA_NAO_ENDERECADOS) {
			if (query(request).containsKey(naoImplementado)) {
				throw s3Error(501, "NotImplemented", "O subrecurso '" + naoImplementado
					+ "' nao e suportado; " + chaveDoObjeto + " nao foi lido.");
			}
		}
	}

	/**
	 * O {@code DELETE} de subrecurso no <b>bucket</b> nao e {@code DeleteBucket}.
	 *
	 * <p>{@code ?cors}, {@code ?tagging}, {@code ?policy}, {@code ?website} e os demais sao
	 * politicas do bucket no S3. Aqui a unica que age e a do varredor ({@code ?lifecycle}, §4.36).
	 * Sem a guarda, {@code DELETE /{bucket}?cors} entrava no {@code DeleteBucket}: com objeto vivo
	 * respondia {@code 409 BucketNotEmpty} (mentindo sobre o motivo) e num bucket vazio apagaria o
	 * bucket em resposta a "remova a CORS". A recusa e {@code 501}, como no {@code PUT} de
	 * subrecurso que gravaria (§4.21).</p>
	 */
	private static void recusarSubrecursoNoBucket(HttpServletRequest request, String bucket) {
		for (String subrecurso : SUBRECURSOS_DE_BUCKET_NAO_IMPLEMENTADOS) {
			if (query(request).containsKey(subrecurso)) {
				throw s3Error(501, "NotImplemented", "O subrecurso de bucket '" + subrecurso
					+ "' nao e suportado; o bucket " + bucket + " nao foi alterado.");
			}
		}
	}

	/**
	 * Os cabeçalhos de SSE que o cliente pede e este gateway não aplica.
	 *
	 * <p><b>Antes:</b> eram ignorados. O {@code PUT} com {@code x-amz-server-side-encryption: AES256}
	 * — o que o SDK manda quando SSE está configurado — respondia {@code 200}, gravava em claro e não
	 * devolvia o cabeçalho nem no {@code HEAD}. O cliente ficava com a certeza de que a borda cifra o
	 * objeto; quem lê a cópia em disco vê o conteúdo.</p>
	 *
	 * <p><b>Agora:</b> {@code 501 NotImplemented} antes de abrir upload, como o subrecurso não
	 * implementado: a recusa diz qual cabeçalho e que nada foi gravado (§4.48). A cifra em repouso é
	 * do sistema de arquivos do nó, não de uma opção por requisição — aceitar o pedido seria prometer
	 * uma garantia que o gateway não cumpre.</p>
	 */
	private static void recusarSseNaoAplicavel(HttpServletRequest request) {
		for (String cabecalho : CABECALHOS_DE_SSE) {
			if (request.getHeader(cabecalho) != null) {
				throw s3Error(501, "NotImplemented", "O cabecalho '" + cabecalho
					+ "' nao e suportado: este gateway nao cifra por requisicao, e o objeto nao foi gravado.");
			}
		}
	}

	/** Os cabeçalhos de Server-Side Encryption que o gateway recusa (§4.48). */
	private static final List<String> CABECALHOS_DE_SSE = List.of("x-amz-server-side-encryption",
		"x-amz-server-side-encryption-customer-algorithm", "x-amz-server-side-encryption-aws-kms-key-id");

	/**
	 * {@code x-amz-storage-class} além de {@code STANDARD}, que é a única classe que o gateway faz.
	 *
	 * <p><b>Antes:</b> o cabeçalho era ignorado e todo {@code PUT} respondia {@code 200}. Quem manda
	 * {@code GLACIER} (o SDK de arquivamento) levava a certeza de que o objeto estava na classe fria,
	 * e o objeto estava em disco quente, servido na hora.</p>
	 *
	 * <p><b>Agora:</b> {@code 501 NotImplemented} nomeando a classe, antes de abrir upload — como o
	 * subrecurso não implementado (§4.21). Recusar diz onde o dado está; responder sucesso com outro
	 * rótulo seria a mentira que este gateway evita (§4.49).</p>
	 */
	private static void recusarClasseDeArmazenamentoNaoImplementada(HttpServletRequest request) {
		String classe = request.getHeader("x-amz-storage-class");
		if (classe == null || classe.isBlank() || "STANDARD".equalsIgnoreCase(classe.trim())) {
			return;
		}
		throw s3Error(501, "NotImplemented", "A classe de armazenamento '" + classe.trim()
			+ "' nao e suportada: este gateway so guarda STANDARD, e o objeto nao foi gravado.");
	}

	/**
	 * Emite o {@code NextContinuationToken}: a última chave emitida, assinada com o segredo da
	 * credencial e o bucket (§4.53).
	 *
	 * <p><b>Antes:</b> o token era a última chave <b>em claro</b>, usada como posição sem conferência
	 * nenhuma. Qualquer string virava posição: {@code continuation-token=lixo} respondia {@code 200}
	 * com lista vazia, e o cliente concluía que o bucket acabou; o token de um bucket servia no outro.</p>
	 *
	 * <p><b>Agora:</b> {@code base64(posição)} + {@code "."} + HMAC-SHA-256 de "bucket posição"
	 * truncado. O que não confere é {@code 400 InvalidArgument}. Não é um MAC de segurança de dados —
	 * é a prova de que o token veio daqui, para este bucket, com esta credencial.</p>
	 */
	private String emitirTokenDeContinuacao(S3Credential credential, String bucket, String ultimaChave) {
		String segredo = segredoDoToken(credential);
		String corpo = java.util.Base64.getUrlEncoder().withoutPadding()
			.encodeToString(ultimaChave.getBytes(StandardCharsets.UTF_8));
		return corpo + "." + assinaturaDoToken(segredo, bucket, ultimaChave);
	}

	/**
	 * O segredo que assina o token: o mesmo com que a requisição foi verificada.
	 *
	 * <p>A requisição de listagem passou pela SigV4 com este segredo; chegar aqui sem ele é a
	 * credencial inconsistente na base, não o cliente. Devolver um token que o leitor recusa seria
	 * uma paginação que morre na segunda página.</p>
	 */
	private String segredoDoToken(S3Credential credential) {
		String segredo = credentials.secretFor(credential.id());
		if (segredo == null) {
			throw s3Error(403, "SignatureDoesNotMatch",
				"Credencial sem segredo guardado nao pode assinar; gere uma nova.");
		}
		return segredo;
	}

	/** A posição de uma `continuation-token`, ou {@code 400} se o token não foi emitido aqui. */
	private String tokenDeContinuacao(S3Credential credential, String bucket, String token) {
		String segredo = segredoDoToken(credential);
		int ponto = token.lastIndexOf('.');
		if (ponto <= 0 || ponto == token.length() - 1) {
			throw s3Error(400, "InvalidArgument",
				"O continuation-token nao foi emitido por este gateway para este bucket.");
		}
		String chave;
		try {
			chave = new String(java.util.Base64.getUrlDecoder().decode(token.substring(0, ponto)), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException naoEhBase64) {
			throw s3Error(400, "InvalidArgument",
				"O continuation-token nao foi emitido por este gateway para este bucket.");
		}
		// Comparação em tempo fixo: o token é público, mas comparar sem curto-circuito é a regra.
		if (!java.security.MessageDigest.isEqual(assinaturaDoToken(segredo, bucket, chave).getBytes(StandardCharsets.US_ASCII),
				token.substring(ponto + 1).getBytes(StandardCharsets.US_ASCII))) {
			throw s3Error(400, "InvalidArgument",
				"O continuation-token nao foi emitido por este gateway para este bucket.");
		}
		return chave;
	}

	/** HMAC-SHA-256 de {@code bucket + "\n" + chave}, hexadecimal. */
	private static String assinaturaDoToken(String segredo, String bucket, String chave) {
		try {
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new javax.crypto.spec.SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return java.util.HexFormat.of().formatHex(
				mac.doFinal((bucket + "\n" + chave).getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.GeneralSecurityException semHmac) {
			throw new IllegalStateException("HmacSHA256 nao disponivel", semHmac);
		}
	}

	/**
	 * Cabeçalhos de objeto que o cliente declara e que o gateway não guarda.
	 *
	 * <p><b>Antes:</b> {@code Expires}, {@code Content-Language} e
	 * {@code x-amz-website-redirect-location} eram aceitos com {@code 200} e descartados — não há
	 * coluna para eles, e o {@code HEAD}/{@code GET} não os devolviam. Quem manda {@code Expires}
	 * acredita que o objeto apaga sozinho; quem manda o redirecionamento acredita que o {@code GET}
	 * devolve um {@code 301}; quem manda o idioma espera a fonte certa no navegador. Nada disso
	 * acontecia, e a falta do eco não avisa quem não relê o {@code HEAD}.</p>
	 *
	 * <p><b>Agora:</b> {@code 501 NotImplemented} nomeando o cabeçalho, antes de abrir upload — no
	 * {@code PUT}, na cópia e no multipart (§4.51). Os que a §3 promete ({@code x-amz-meta-*},
	 * {@code Cache-Control}, {@code Content-Disposition}) continuam voltando.</p>
	 */
	private static void recusarMetadadoDeObjetoNaoGuardado(HttpServletRequest request) {
		for (String cabecalho : METADADOS_DE_OBJETO_NAO_GUARDADOS) {
			if (request.getHeader(cabecalho) != null) {
				throw s3Error(501, "NotImplemented", "O cabecalho '" + cabecalho
					+ "' nao e suportado: este gateway nao guarda esse metadado, e o objeto nao foi gravado.");
			}
		}
	}

	/** Metadado de objeto declarado no upload que o catálogo não tem onde guardar (§4.51). */
	private static final List<String> METADADOS_DE_OBJETO_NAO_GUARDADOS = List.of("Expires",
		"Content-Language", "x-amz-website-redirect-location");

	/**
	 * Subrecurso da consulta que o gateway não implementa e que, sem esta guarda, seria gravado.
	 *
	 * <p><b>Antes:</b> o {@code PUT} era despachado pela chave da rota, sem olhar a consulta. Um
	 * {@code PUT ?tagging}, {@code ?acl}, {@code ?retention}, {@code ?legal-hold} ou {@code ?object-lock}
	 * abria um upload da chave, gravava o <b>XML daquela operação</b> como corpo, substituía o arquivo
	 * de verdade e respondia {@code 200} — que é o "sucesso" de várias operações do S3 que este gateway
	 * não implementa. O cliente via sucesso e o objeto ficava com o XML dentro.</p>
	 *
	 * <p><b>Agora:</b> {@code 501 NotImplemented} no XML do S3, devolvendo o subrecurso não suportado,
	 * e nada é gravado. A lista vizinha (ecoada em {@code 200} sem gravar) já fazia isso para
	 * {@code ?versioning}, {@code ?encryption}, {@code ?logging} e companhia; o que distingue os dois
	 * grupos é justamente o que acontece sem a guarda — ali, um corpo vazio confundido com objeto;
	 * aqui, o objeto sobrescrito.</p>
	 */
	private static void recusarSubrecurso(HttpServletRequest request, String chaveDoObjeto) throws IOException {
		Set<String> pedidos = query(request).keySet();
		for (String naoImplementado : SUBRECURSOS_QUE_GRAVARIAM) {
			if (pedidos.contains(naoImplementado)) {
				// A recusa acontece antes de o corpo ser lido, e o corpo do `?tagging` é o XML da
				// etiqueta. Em conexão persistente, responder 501 com o corpo pendente faz o servidor
				// fechar a conexão no meio de um pedido; drenar (e descartar) deixa a próxima
				// requisição da mesma conexão chegar inteira. RFC 9110 §15.6.2.
				drenarCorpo(request);
				throw s3Error(501, "NotImplemented", "O subrecurso '" + naoImplementado + "' nao e suportado; "
					+ chaveDoObjeto + " nao foi alterado.");
			}
		}
	}

	/**
	 * Subrecurso que o {@code DELETE} de objeto não endereça: {@code 501} com a chave <b>intacta</b>.
	 *
	 * <p>A resposta é a mesma do {@code PUT} para os subrecursos que gravariam (§4.21). O
	 * {@code ?uploadId=} fica de fora: é o {@code AbortMultipartUpload}, outra operação.</p>
	 */
	private static void recusarSubrecursoNaoApagavel(HttpServletRequest request, String chaveDoObjeto) {
		Set<String> pedidos = query(request).keySet();
		for (String naoApagavel : SUBRECURSOS_NAO_APAGAVEIS) {
			if (pedidos.contains(naoApagavel)) {
				throw s3Error(501, "NotImplemented", "O subrecurso '" + naoApagavel + "' nao e suportado; "
					+ chaveDoObjeto + " nao foi apagado.");
			}
		}
	}

	/** Lê e descarta o corpo já enviado, sem guardar. Sem corpo, não há nada a drenar. */
	static void drenarCorpo(HttpServletRequest request) throws IOException {
		try (InputStream corpo = request.getInputStream()) {
			corpo.transferTo(OutputStream.nullOutputStream());
		} catch (IOException recusado) {
			// O cliente pode já ter cortado o envio ao receber a recusa; a resposta é a mesma.
		}
	}

	/**
	 * A chave apagada por <b>outra conta</b> ainda está resolvida pela lápide dela.
	 *
	 * <p><b>Antes:</b> a recusa por donagem conferia o bucket pelo que estava visível — a lápide do
	 * dono e o objeto do intruso apareciam juntos, e a gravação do intruso passava. Na pior ordem de
	 * eventos, um {@code PUT} numa chave vizinha depois do {@code DELETE} do dono deixava a chave
	 * <b>inteira</b> com o objeto do intruso (tipo, metadado e nome de anexo dele) e respondia
	 * {@code 200}: o {@code DELETE} do dono não entregava o nome da chave.</p>
	 *
	 * <p><b>Agora:</b> a lápide é uma tomada de posse — a chave só volta a aceitar escrita de quem
	 * a apagou. É a regra do bucket aplicada à chave, e a resposta continua no vocabulário do S3
	 * ({@code 403 AccessDenied}).</p>
	 */
	private void recusarChaveDeOutraConta(S3Credential credential, String bucket, String key) {
		catalog.find(bucket, key).ifPresent(atual -> {
			if (atual.state() == StorageObjectState.DELETED && !credential.ownerUserId().equals(atual.createdBy())) {
				throw s3Error(403, "AccessDenied", "A chave foi removida por outra conta.");
			}
		});
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
	ResponseEntity<?> putObject(@PathVariable String bucket, HttpServletRequest request) throws IOException {
		S3Credential credential = authenticate(request, "PUT");
		requireBucket(credential, bucket);
		String key = keyOf(request);
		// Subrecurso que este gateway não implementa NÃO é PutObject: recusa antes de tocar no
		// catálogo e antes do desvio para a cópia. Ver `recusarSubrecurso`.
		recusarSubrecurso(request, "/" + bucket + "/" + key);
		// A chave apagada por outra conta é recusa antes de abrir: a lápide é tomada de posse (§4.22).
		recusarChaveDeOutraConta(credential, bucket, key);
		// SSE pedido e não aplicado é sucesso falso: recusa antes de abrir, no PUT e na cópia (§4.48).
		recusarSseNaoAplicavel(request);
		// Classe além de STANDARD é recusa antes de abrir, no PUT e na cópia (§4.49).
		recusarClasseDeArmazenamentoNaoImplementada(request);
		// Metadado que o catálogo não guarda é recusa antes de abrir, no PUT e na cópia (§4.51).
		recusarMetadadoDeObjetoNaoGuardado(request);
		String origemDaCopia = request.getHeader(COPY_SOURCE);
		if (origemDaCopia != null && !origemDaCopia.isBlank()) {
			return copiarObjeto(credential, bucket, key, origemDaCopia, request);
		}
		// O tipo é o que o SDK manda; sem ele, o mesmo padrão do resto do módulo.
		String contentType = request.getContentType();
		// Refazer um PUT na mesma chave é o caso normal do S3: `beginUpload` devolve o objeto que já
		// existe, que pode estar STORED. Guardar o estado de antes é o que permite devolvê-lo intacto
		// se a gravação for recusada por donagem (§4.22).
		StorageObject antes = catalog.find(bucket, key).orElse(null);
		// `If-None-Match`/`If-Match` no PUT: o cliente pediu uma CONDIÇÃO, e a resposta de "a chave
		// existe" é `412 PreconditionFailed`, não `409 ObjectAlreadyStored` (que é a confissão de que
		// o gateway não sobrescreve). Antes os dois cabeçalhos eram ignorados: `--if-none-match '*'`
		// — o jeito de criar sem sobrescrever — recebia a recusa da sobrescrita ou, com o objeto
		// PENDING, gravava por cima. A conferência vem antes de abrir upload (§4.29).
		// Um `PENDING` é gravação em curso, não versão publicada: o `sha256` dele é nulo, e comparar
		// com ele faria `If-Match` do ETag confirmado anterior falhar só por haver upload na vez.
		exigirPrecondicoesDoPut(request, antes != null && antes.state() == StorageObjectState.PENDING ? null : antes);
		// `Content-MD5` e `x-amz-checksum-*`: conferidos ANTES de abrir upload (§4.44, §4.46). Um digest
		// que não bate recusa aqui, sem deixar linha aberta nem objeto PENDING atrás — por isso o corpo
		// é lido para memória quando algum cabeçalho de integridade veio (quem pede conferência já
		// aceita o custo dela).
		byte[] md5Declarado = md5Declarado(request);
		CheckSumDeclarado checksum = checksumDeclarado(request);
		byte[] corpoConferido = null;
		if (md5Declarado != null || checksum != null) {
			try {
				corpoConferido = body(request).readAllBytes();
			}
			catch (java.io.IOException falha) {
				throw s3Error(400, "InvalidRequest", "Nao foi possivel ler o corpo.");
			}
			if (md5Declarado != null) {
				exigirMd5Conferido(corpoConferido, md5Declarado);
			}
			if (checksum != null) {
				checksum.conferir(corpoConferido);
			}
		}
		// O retrato do que a linha declarava, lido antes de `saveClientHeaders` trocá-lo (§4.22).
		StorageCatalogService.ClientHeaders antesDeclarados = StorageCatalogService.ClientHeaders.NONE;
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
		// O retrato de antes é a linha que o `finishUpload` vai gravar, lida <b>depois</b> da abertura e
		// <b>antes</b> do `saveClientHeaders`: `beginUpload` devolve a linha da chave — a mesma, se ela
		// ainda está viva, ou a nova que substitui a aposentada — com o conteúdo e o metadado que ela
		// tem. Se a gravação for recusada, é isto que volta.
		antes = pending;
		// O `saveClientHeaders` troca o que a linha declarava pelos desta requisição — e um `PUT` sem
		// `x-amz-meta-*` apagaria o metadado do dono. O que a linha já tinha é lido ANTES e volta na
		// recusa, junto do resto do retrato.
		antesDeclarados = storage.declaredHeaders(antes);
		storage.saveClientHeaders(pending, declarados);
		// "Criar" é a linha ainda sem corpo: a chave nova e a que uma linha anterior deixou sem conteúdo.
		// Reescrever um objeto confirmado é 409, o mesmo contrato de antes desta mudança. Numa chave nova
		// de outra conta, a recusa por donagem apaga esta linha — sem conteúdo, nada a devolver.
		boolean iaCriar = antes.bytes() == null;
		if (!iaCriar && antes.state() != StorageObjectState.PENDING) {
			throw s3Error(409, "ObjectAlreadyStored", "A chave ja tem copia confirmada; use outro nome de chave.");
		}
		// O catálogo mede o digest em pedaços de 64 KiB e grava; é o mesmo caminho da rota do nó.
		// A recusa por cota de participante é 507 <b>no formato do S3</b> com o código interno: o SDK
		// não conhece "STORAGE_OWNER_QUOTA_EXCEEDED", e um XML sem `Code` que ele entenda viraria
		// falha opaca do cliente em vez de "seu espaço acabou".
		StorageObject stored;
		try {
			// Corpo vazio é objeto válido no S3 (marcador de "pasta" do `aws s3 cp --recursive`):
			// o caminho do cliente aceita 0 byte, o do nó continua recusando.
			stored = storage.finishUploadPermitindoVazio(pending.id(),
				corpoConferido != null ? new java.io.ByteArrayInputStream(corpoConferido) : body(request),
				credential.ownerUserId());
		}
		catch (ApiException recusado) {
			throw noVocabularioDoS3(recusado);
		}
		if (!bucketSemDono(bucket, credential.ownerUserId())) {
			desfazer(stored, antes, antesDeclarados);
			throw s3Error(403, "AccessDenied", "Este bucket nao e do dono da credencial.");
		}
		// A requisição passou: o corpo de antes não serve mais e o backup sai do disco.
		storage.soltarBackup(stored.id());
		return ResponseEntity.ok()
			.eTag("\"" + stored.sha256() + "\"")
			.header(HttpHeaders.CONTENT_LENGTH, "0")
			.build();
	}

	/**
	 * CopyObject: {@code PUT /{bucket}/{key}} com {@code x-amz-copy-source} copia os bytes da origem
	 * para o destino <b>sem o cliente enviar o arquivo</b>.
	 *
	 * <p><b>Antes:</b> o cabeçalho era recusado com {@code 501 NotImplemented} — a operação não existia.
	 * {@code aws s3 cp --recursive} e {@code rclone copy} usam a cópia no servidor para não baixar e
	 * reenviar cada arquivo; sem ela, um cliente que não cai para o caminho lento (baixar e mandar de
	 * novo) falha no item, e o que cai gasta a banda duas vezes.</p>
	 *
	 * <p><b>Agora:</b> a origem é lida pelo mesmo caminho de download ({@code readBody}, que só entrega
	 * o que o catálogo tem como servível e confere a réplica 0) e atravessa o mesmo
	 * {@code finishUpload} do PUT. Nada é carregado em memória: o corpo vai do disco de origem ao
	 * temporário da gravação em blocos de 64 KiB. O destino é um objeto novo do mesmo dono da
	 * credencial; o ETag devolvido é o sha-256 do que foi escrito, não o da origem — se os dois
	 * divergirem, o que vale é o que está no disco.</p>
	 *
	 * <p><b>Cabeçalhos:</b> {@code x-amz-metadata-directive} aceita {@code COPY} (padrão: content-type,
	 * metadado, Cache-Control e Content-Disposition da origem) e {@code REPLACE} (o que veio nesta
	 * requisição). {@code x-amz-copy-source-if-match} e {@code -if-none-match} são conferidos contra o
	 * ETag da origem; as condições por data respondem {@code 501} em vez de serem ignoradas — uma
	 * condição aceita e não aplicada é uma resposta errada com cara de certa. {@code versionId} fica
	 * de fora: a versão corrente é a única que o gateway endereça.</p>
	 *
	 * <p><b>Semântica herdada do PUT:</b> copiar para uma chave que já tem cópia confirmada responde
	 * {@code 409 ObjectAlreadyStored} (o gateway não sobrescreve; um bucket versionado cria a versão
	 * nova, como no PUT) e copiar um objeto sobre ele mesmo responde {@code 400 InvalidRequest}.</p>
	 */
	private ResponseEntity<?> copiarObjeto(S3Credential credential, String bucket, String key,
			String cabecalhoDaOrigem, HttpServletRequest request) {
		// O `x-amz-copy-source-range` é do UploadPartCopy; no CopyObject o S3 não o aceita. Antes ele
		// caía no vazio e a cópia gravava a origem INTEIRA com 200 — quem pedia 5 bytes recebia 10.
		if (request.getHeader("x-amz-copy-source-range") != null) {
			throw s3Error(400, "InvalidArgument",
				"x-amz-copy-source-range nao vale no CopyObject; use UploadPartCopy (PUT com partNumber e uploadId).");
		}
		OrigemDaCopia origem = origemDaCopia(cabecalhoDaOrigem);
		StorageObject fonte = origemServivel(credential, origem);
		exigirPrecondicoesDaCopia(request, fonte);

		boolean mesmoObjeto = origem.bucket().equals(bucket)
			&& (origem.key().equals(key) || origem.chaveBruta().equals(key));
		if (mesmoObjeto) {
			throw s3Error(400, "InvalidRequest",
				"Copiar um objeto sobre ele mesmo nao e suportado; use PUT para trocar o conteudo.");
		}
		// A donagem do destino é conferida ANTES da abertura: uma cópia para bucket de outra conta não
		// chega a reservar chave. O PUT precisa gravar primeiro (um bucket novo nasce do primeiro
		// objeto); a cópia já sabe que o destino existe e de quem é.
		if (!bucketSemDono(bucket, credential.ownerUserId())) {
			throw s3Error(403, "AccessDenied", "Este bucket nao e do dono da credencial.");
		}

		boolean substituirMetadado = diretivaDeMetadadoSubstitui(request);
		StorageCatalogService.ClientHeaders cabecalhos = substituirMetadado ? clientHeaders(request)
			: new StorageCatalogService.ClientHeaders(fonte.metadata(), fonte.cacheControl(),
				fonte.contentDisposition());
		// O tipo do destino é o da origem no COPY e o declarado na requisição no REPLACE. O
		// `beginUpload` é quem grava o content-type; `saveClientHeaders` não o carrega.
		String tipoDoDestino = substituirMetadado ? request.getContentType() : fonte.contentType();
		StorageObject antes = catalog.find(bucket, key).orElse(null);
		StorageObject pending;
		try {
			pending = storage.beginUpload(credential.ownerUserId(), bucket, key, tipoDoDestino);
		}
		catch (ApiException recusado) {
			throw noVocabularioDoS3(recusado);
		}
		boolean iaCriar = antes == null || !antes.id().equals(pending.id());
		if (!iaCriar && antes.state() != StorageObjectState.PENDING) {
			// A recusa vem antes de `saveClientHeaders`: reescrever metadado de um objeto confirmado
			// que a resposta diz não ter sido tocado deixaria o catálogo mentindo sobre o conteúdo.
			throw s3Error(409, "ObjectAlreadyStored",
				"A chave ja tem copia confirmada; use outro nome de chave.");
		}
		StorageCatalogService.ClientHeaders cabecalhosDeAntes = pending.id().equals(antes == null ? null : antes.id())
			? storage.declaredHeaders(antes) : StorageCatalogService.ClientHeaders.NONE;
		storage.saveClientHeaders(pending, cabecalhos);
		StorageObject stored;
		try (InputStream corpoDaOrigem = storage.readBody(fonte.id())) {
			stored = storage.finishUploadPermitindoVazio(pending.id(), corpoDaOrigem, credential.ownerUserId());
		}
		catch (ApiException recusado) {
			throw noVocabularioDoS3(recusado);
		}
		catch (java.io.IOException fechou) {
			throw s3Error(500, "InternalError", "A origem nao pode ser lida para a copia.");
		}
		if (!bucketSemDono(bucket, credential.ownerUserId())) {
			// Corrida: o bucket passou a ter objeto de outra conta entre a conferência e a gravação.
			desfazer(stored, antes, cabecalhosDeAntes);
			throw s3Error(403, "AccessDenied", "Este bucket nao e do dono da credencial.");
		}
		storage.soltarBackup(stored.id());
		S3Xml xml = new S3Xml();
		xml.root("CopyObjectResult").element("ETag", "\"" + stored.sha256() + "\"")
			.element("LastModified", lastModified(stored));
		return xml(HttpStatus.OK).eTag("\"" + stored.sha256() + "\"").body(xml.toString());
	}

	/**
	 * A origem de uma cópia: do dono da credencial, dentro do escopo e servível.
	 *
	 * <p>É a mesma régua do {@code CopyObject} e do {@code UploadPartCopy} — um lugar só para as duas
	 * operações. `bucketsOf` só conhece bucket que já tem objeto vivo; um bucket vazio (inclusive o
	 * que ainda não existe) não tem dono, e a resposta certa para a origem ausente é 404, não 403.</p>
	 */
	private StorageObject origemServivel(S3Credential credential, OrigemDaCopia origem) {
		// O escopo da credencial vale para a origem também: ler de um bucket fora do escopo não é
		// uma operação que a credencial autoriza, ainda que o dono seja o mesmo.
		requireBucket(credential, origem.bucket());
		boolean bucketDaOrigemEhNossa = catalog.bucketsOf(credential.ownerUserId()).contains(origem.bucket())
			|| bucketSemDono(origem.bucket(), credential.ownerUserId());
		if (!bucketDaOrigemEhNossa) {
			throw s3Error(403, "AccessDenied", "O bucket de origem nao e do dono da credencial.");
		}
		StorageObject fonte;
		try {
			fonte = storage.requireDownloadable(origem.bucket(), origem.key(), "GET");
		}
		catch (ApiException naoAchou) {
			// Duas formas de chave convivem na instalação: o caminho do PUT guarda o que veio na URI
			// (percent-encoded) e um cliente pode mandar a chave já decodificada no cabeçalho. Tentar
			// as duas é o que faz a cópia encontrar o objeto nas duas.
			if (origem.key().equals(origem.chaveBruta())) {
				throw noVocabularioDoS3(naoAchou);
			}
			try {
				fonte = storage.requireDownloadable(origem.bucket(), origem.chaveBruta(), "GET");
			}
			catch (ApiException tambemNaoAchou) {
				throw noVocabularioDoS3(tambemNaoAchou);
			}
		}
		if (!credential.ownerUserId().equals(fonte.createdBy())) {
			// A donagem do bucket pode ter mudado entre a lista e a leitura; o objeto é a verdade.
			throw s3Error(403, "AccessDenied", "O objeto de origem nao e do dono da credencial.");
		}
		return fonte;
	}

	/** A origem de uma cópia, com a chave como veio e a chave decodificada. */
	private record OrigemDaCopia(String bucket, String key, String chaveBruta) {
	}

	/**
	 * {@code x-amz-copy-source} é um <b>caminho</b>: {@code /bucket/chave}, com percent-encoding.
	 *
	 * <p>{@code +} não vira espaço — essa regra é de query string. O SDK manda {@code %2B} para uma
	 * chave com mais, mas um {@code +} literal também é uma chave com mais; tratar os dois como espaço
	 * faria a cópia procurar um objeto que não existe.</p>
	 */
	private static OrigemDaCopia origemDaCopia(String cabecalho) {
		String valor = cabecalho.trim();
		if (valor.startsWith("/")) {
			valor = valor.substring(1);
		}
		if (valor.indexOf('?') >= 0) {
			// `?versionId=` não é endereçável pelo gateway (a versão corrente é a única que ele serve).
			throw s3Error(501, "NotImplemented",
				"Copiar uma versao especifica (versionId) nao e suportado.");
		}
		int barra = valor.indexOf('/');
		if (barra <= 0 || barra == valor.length() - 1) {
			throw s3Error(400, "InvalidArgument", "x-amz-copy-source precisa ser /bucket/chave.");
		}
		String bruta = valor.substring(barra + 1);
		return new OrigemDaCopia(decodificaCaminho(valor.substring(0, barra)), decodificaCaminho(bruta), bruta);
	}

	private static String decodificaCaminho(String valor) {
		try {
			// `%2B` é o mais; `+` literal também. Trocar o literal por `%2B` antes de decodificar
			// preserva os dois e ainda aceita `%20` para espaço.
			return java.net.URLDecoder.decode(valor.replace("+", "%2B"), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException invalido) {
			throw s3Error(400, "InvalidArgument", "x-amz-copy-source tem percent-encoding invalido.");
		}
	}

	/**
	 * As condições da origem. As de ETag são aplicadas; as de data são recusadas com {@code 501} em vez
	 * de ignoradas — aceitar o cabeçalho e copiar mesmo assim seria uma resposta que mente.
	 */
	private static void exigirPrecondicoesDaCopia(HttpServletRequest request, StorageObject fonte) {
		if (request.getHeader("x-amz-copy-source-if-modified-since") != null
				|| request.getHeader("x-amz-copy-source-if-unmodified-since") != null) {
			throw s3Error(501, "NotImplemented",
				"As condicoes de data da copia nao sao suportadas; use if-match ou if-none-match.");
		}
		String ifMatch = request.getHeader("x-amz-copy-source-if-match");
		if (ifMatch != null && !etagNaLista(ifMatch, fonte)) {
			throw s3Error(412, "PreconditionFailed", "O ETag da origem nao confere com If-Match.");
		}
		String ifNoneMatch = request.getHeader("x-amz-copy-source-if-none-match");
		if (ifNoneMatch != null && etagNaLista(ifNoneMatch, fonte)) {
			throw s3Error(412, "PreconditionFailed", "O ETag da origem confere com If-None-Match.");
		}
	}

	/**
	 * As condições do PUT contra o objeto que já está na chave (pode ser {@code null}).
	 *
	 * <p>{@code If-None-Match: *} é "só se não existir"; {@code If-Match} é "só se for esta versão".
	 * As de data não são implementadas e respondem {@code 501} em vez de serem ignoradas, como as da
	 * cópia. Chave livre com {@code If-Match} não confere: não há versão para casar.</p>
	 */
	private static void exigirPrecondicoesDoPut(HttpServletRequest request, StorageObject existente) {
		if (request.getHeader("If-Modified-Since") != null || request.getHeader("If-Unmodified-Since") != null) {
			throw s3Error(501, "NotImplemented",
				"As condicoes de data no PUT nao sao suportadas; use If-Match ou If-None-Match.");
		}
		String ifMatch = request.getHeader("If-Match");
		if (ifMatch != null && !ifMatch.isBlank()) {
			if (existente == null || !etagNaLista(ifMatch, existente)) {
				throw s3Error(412, "PreconditionFailed", "O objeto nao confere com If-Match.");
			}
		}
		String ifNoneMatch = request.getHeader("If-None-Match");
		if (ifNoneMatch != null && !ifNoneMatch.isBlank() && existente != null
				&& etagNaLista(ifNoneMatch, existente)) {
			throw s3Error(412, "PreconditionFailed", "A chave ja existe (If-None-Match).");
		}
	}

	static boolean etagNaLista(String cabecalho, StorageObject fonte) {
		// `sha256` nulo é linha `PENDING` (gravação em curso) ou sem corpo: não é versão. Sem ETag,
		// só o asterisco casa — e ele casa porque a chave <b>existe</b>. Comparar o nulo com
		// `"null"` fazia o `If-Match` do ETag confirmado falhar por causa de um upload na vez.
		if (fonte.sha256() == null) {
			return "*".equals(cabecalho.trim());
		}
		String comAspas = "\"" + fonte.sha256() + "\"";
		for (String candidato : cabecalho.split(",")) {
			String valor = candidato.trim();
			if ("*".equals(valor) || comAspas.equals(valor) || fonte.sha256().equals(valor)) {
				return true;
			}
		}
		return false;
	}

	/** {@code COPY} (padrão) mantém o metadado da origem; {@code REPLACE} usa o da requisição. */
	private static boolean diretivaDeMetadadoSubstitui(HttpServletRequest request) {
		String diretiva = request.getHeader("x-amz-metadata-directive");
		if (diretiva == null || diretiva.isBlank()) {
			return false;
		}
		if ("REPLACE".equalsIgnoreCase(diretiva.trim())) {
			return true;
		}
		if ("COPY".equalsIgnoreCase(diretiva.trim())) {
			return false;
		}
		throw s3Error(400, "InvalidArgument", "x-amz-metadata-directive aceita COPY ou REPLACE.");
	}

	/**
	 * Desfaz uma gravação recusada por donagem.
	 *
	 * <p>O que conta é <b>a linha desta gravação</b>, não {@code stored.id()}: quando a chave já existia,
	 * {@code finishUpload} devolve o <i>mesmo</i> id, e apagar a linha seria apagar o objeto do dono — o
	 * oposto do que a recusa quer. Por isso só a linha que ainda não tinha corpo é removida; o que
	 * existia antes volta inteiro — estado, conteúdo, tipo, metadado e prazo —, e o corpo já gravado
	 * pelo {@code finishUpload} é trocado pelos bytes de antes.</p>
	 */
	private void desfazer(StorageObject stored, StorageObject antes,
			StorageCatalogService.ClientHeaders antesDeclarados) {
		if (antes.bytes() != null || antes.state() != StorageObjectState.PENDING) {
			// A chave já tinha objeto: o que a recusa devolve é o retrato de antes — estado, conteúdo,
			// tipo, metadado e prazo —, porque o `finishUpload` deixou ali o da gravação recusada.
			// Apagar a linha seria apagar o objeto do dono.
			// Pelo serviço, não pelo repositório: é ele que guarda o corpo de antes e o devolve ao
			// disco. Só os metadados, o HEAD reportava o retrato do dono e o GET servia os bytes da
			// gravação recusada.
			storage.restorePending(stored.id(), antes.contentType(), antes.bytes(), antes.sha256(),
				antesDeclarados.metadata(), antesDeclarados.cacheControl(), antesDeclarados.contentDisposition(),
				antes.expiresAt());
			return;
		}
		// A linha era desta gravação — sem conteúdo e sem estado. Apagá-la é o que impede o objeto
		// recusado de dar o bucket ao intruso.
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
		// O multipart também prometeria cifra que o gateway não faz (§4.48). Antes do
		// `requireBucket`: com a recusa a chave nunca existe, e a donagem responderia `403`.
		recusarSseNaoAplicavel(request);
		// O multipart também prometeria uma classe que o gateway não faz (§4.49).
		recusarClasseDeArmazenamentoNaoImplementada(request);
		// E um metadado que o catálogo não guarda (§4.51).
		recusarMetadadoDeObjetoNaoGuardado(request);
		requireBucket(credential, bucket);
		String key = keyOf(request);
		String expires = query(request).get("expires");
		Long validade;
		try {
			validade = expires == null ? null : Long.valueOf(expires);
		}
		catch (NumberFormatException naoEhNumero) {
			throw s3Error(400, "InvalidArgument", "expires precisa ser um numero de segundos.");
		}
		com.socialcoisa.rede.network.application.StorageMultipartService.Upload upload;
		try {
			upload = multipart.create(credential.ownerUserId(), credential.id(), bucket, key,
				request.getContentType(), validade);
		}
		catch (ApiException recusado) {
			// `expires` fora do intervalo é recusa do serviço; sem tradução, saía JSON no gateway.
			throw noVocabularioDoS3(recusado);
		}
		var comCabecalhos = upload.comCabeçalhos(clientHeaders(request));
		if (!bucketSemDono(bucket, credential.ownerUserId())) {
			multipart.abort(comCabecalhos.uploadId());
			throw s3Error(403, "AccessDenied", "Este bucket nao e do dono da credencial.");
		}
		S3Xml xml = new S3Xml();
		xml.root("InitiateMultipartUploadResult").element("Bucket", bucket).element("Key", key)
			.element("UploadId", comCabecalhos.uploadId());
		return xml(HttpStatus.OK).body(xml.toString());
	}

	/**
	 * UploadPart: {@code PUT /{bucket}/{key}?partNumber=N&uploadId=...}.
	 *
	 * <p>Com {@code x-amz-copy-source} é o <b>UploadPartCopy</b>: a parte vem de um intervalo da
	 * origem, não do corpo da requisição ({@link #copiarParte}).</p>
	 *
	 * <p>A credencial é a do upload aberto (e do dono dela) — não uma assinatura nova. É o que impede
	 * que uma parte entre no upload por uma conta que não abriu nenhum.</p>
	 */
	@RequestMapping(value = "/{bucket}/{*key}", method = RequestMethod.PUT, params = {
			"partNumber", "uploadId" })
	ResponseEntity<?> uploadPart(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "PUT");
		requireBucket(credential, bucket);
		java.util.Map<String, String> query = query(request);
		var upload = uploadDaChave(credential, bucket, request);
		int numero;
		try {
			numero = Integer.parseInt(query.get("partNumber"));
		}
		catch (NumberFormatException notNumber) {
			throw s3Error(400, "InvalidArgument", "partNumber invalido.");
		}
		String origemDaCopia = request.getHeader(COPY_SOURCE);
		if (origemDaCopia != null && !origemDaCopia.isBlank()) {
			return copiarParte(credential, upload, numero, origemDaCopia, request);
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
	 * UploadPartCopy: {@code PUT ?partNumber=&uploadId=} com {@code x-amz-copy-source} copia um
	 * <b>intervalo</b> da origem para dentro do upload aberto.
	 *
	 * <p><b>Antes:</b> a parte com o cabeçalho respondia {@code 501}; copiar um arquivo grande em
	 * partes (o caminho de {@code rclone} e do {@code aws s3 cp} acima de 8 MB) não existia.</p>
	 *
	 * <p><b>Agora:</b> {@code x-amz-copy-source-range: bytes=primeiro-ultimo} (inclusive, base zero)
	 * recorta a origem; sem o cabeçalho, a parte é o objeto inteiro. O recorte é lido pelo mesmo
	 * {@code readBody} do download e entra pelo mesmo {@code receivePart} do envio direto — cota,
	 * digest, temporário e substituição de parte reenviada são os mesmos. A conclusão continua
	 * aplicando o mínimo de 5 MiB às partes intermediárias. A resposta é o {@code CopyPartResult} do
	 * S3, com o ETag do que foi gravado.</p>
	 */
	private ResponseEntity<?> copiarParte(S3Credential credential,
			com.socialcoisa.rede.network.application.StorageMultipartService.Upload upload, int numero,
			String cabecalhoDaOrigem, HttpServletRequest request) {
		OrigemDaCopia origem = origemDaCopia(cabecalhoDaOrigem);
		StorageObject fonte = origemServivel(credential, origem);
		exigirPrecondicoesDaCopia(request, fonte);
		long inicio = 0;
		long tamanho = fonte.bytes();
		String faixa = request.getHeader("x-amz-copy-source-range");
		if (faixa != null && !faixa.isBlank()) {
			long[] intervalo = intervaloDaCopia(faixa, fonte.bytes());
			inicio = intervalo[0];
			tamanho = intervalo[1];
		}
		com.socialcoisa.rede.network.application.StorageMultipartService.Part parte;
		try (InputStream corpo = storage.readBody(fonte.id())) {
			corpo.skipNBytes(inicio);
			// O tamanho vai como `anunciado`: a cota recusa a parte antes de qualquer byte, como no
			// envio direto com Content-Length.
			parte = multipart.receivePart(upload, numero, limite(corpo, tamanho), tamanho);
		}
		catch (ApiException recusado) {
			throw noVocabularioDoS3(recusado);
		}
		catch (java.io.IOException falhou) {
			throw s3Error(500, "InternalError", "A origem nao pode ser lida para a copia da parte.");
		}
		S3Xml xml = new S3Xml();
		xml.root("CopyPartResult").element("ETag", "\"" + parte.sha256() + "\"")
			.element("LastModified", S3_INSTANT.format(clock.instant()));
		return xml(HttpStatus.OK).body(xml.toString());
	}

	/**
	 * {@code bytes=primeiro-ultimo}, inclusive, base zero. Fora do objeto, invertido ou malformado é
	 * {@code 400 InvalidArgument}: copiar um intervalo que não existe gravaria uma parte que o cliente
	 * não pediu.
	 */
	private static long[] intervaloDaCopia(String cabecalho, long tamanhoDaOrigem) {
		String valor = cabecalho.trim();
		if (!valor.startsWith("bytes=")) {
			throw s3Error(400, "InvalidArgument",
				"x-amz-copy-source-range precisa ser bytes=primeiro-ultimo.");
		}
		String[] limites = valor.substring("bytes=".length()).split("-", -1);
		if (limites.length != 2 || limites[0].isBlank() || limites[1].isBlank()) {
			throw s3Error(400, "InvalidArgument",
				"x-amz-copy-source-range precisa ser bytes=primeiro-ultimo.");
		}
		long primeiro;
		long ultimo;
		try {
			primeiro = Long.parseLong(limites[0].trim());
			ultimo = Long.parseLong(limites[1].trim());
		}
		catch (NumberFormatException invalido) {
			throw s3Error(400, "InvalidArgument", "x-amz-copy-source-range tem numero invalido.");
		}
		if (primeiro < 0 || ultimo < primeiro || ultimo >= tamanhoDaOrigem) {
			throw s3Error(400, "InvalidArgument",
				"O intervalo pedido nao cabe na origem de " + tamanhoDaOrigem + " bytes.");
		}
		return new long[] { primeiro, ultimo - primeiro + 1 };
	}

	/** Um fluxo que entrega no máximo {@code tamanho} bytes do fluxo da origem. */
	private static InputStream limite(InputStream origem, long tamanho) {
		return new InputStream() {

			private long entregues;

			@Override
			public int read() throws java.io.IOException {
				if (entregues >= tamanho) {
					return -1;
				}
				int lido = origem.read();
				if (lido >= 0) {
					entregues++;
				}
				return lido;
			}

			@Override
			public int read(byte[] destino, int inicio, int quantidade) throws java.io.IOException {
				if (entregues >= tamanho) {
					return -1;
				}
				int lido = origem.read(destino, inicio, (int) Math.min(quantidade, tamanho - entregues));
				if (lido > 0) {
					entregues += lido;
				}
				return lido;
			}

			@Override
			public void close() throws java.io.IOException {
				origem.close();
			}
		};
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
		java.util.List<Parte> pedidas = partesDoXml(body(request));
		var concluido = multipart.complete(upload, pedidas.stream().map(Parte::numero).toList(),
			pedidas.stream().collect(java.util.stream.Collectors.toMap(Parte::numero,
				p -> p.etag() == null ? "" : p.etag(), (a, b) -> a)));
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
		com.socialcoisa.rede.network.application.StorageMultipartService.Upload upload;
		try {
			upload = multipart.require(query(request).get("uploadId"), credential.ownerUserId());
		}
		catch (ApiException recusado) {
			// Upload inexistente, de outra conta ou vencido: o SDK precisa do XML para saber que é
			// `NoSuchUpload` e recomeçar — em JSON ele trata como falha de rede.
			throw noVocabularioDoS3(recusado);
		}
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
		Map<String, String> query = query(request);
		// `part-number-marker` é EXCLUSIVO no S3: a próxima página começa depois dele. Sem isso o
		// cliente que pagina recebia a mesma parte na primeira e na segunda página, e a lista sem
		// `IsTruncated` fazia ele parar convencido de que tinha visto tudo.
		int depoisDe = inteiroOu(query.get("part-number-marker"), 0);
		// `max-parts` ausente ou ilegível é 1000; zero ou negativo cai no mesmo 1000 (o serviço já
		// aplica esse piso). O `<MaxParts>` ecoa o valor APLICADO, não o pedido: antes, com
		// `max-parts=0` a resposta dizia `<MaxParts>0</MaxParts>` e mandava 1000 partes — o XML se
		// contradizia, e o cliente que soma páginas pelo `<MaxParts>` contava errado (§4.54).
		int maxParts = inteiroOu(query.get("max-parts"), 1000);
		int tetoDePartes = maxParts <= 0 ? 1000 : Math.min(maxParts, 1000);
		var pagina = multipart.paginaDePartes(upload.uploadId(), depoisDe, tetoDePartes);
		S3Xml xml = new S3Xml();
		xml.root("ListPartsResult").element("Bucket", bucket).element("Key", upload.key())
			.element("UploadId", upload.uploadId())
			.element("PartNumberMarker", depoisDe).element("MaxParts", tetoDePartes)
			.element("IsTruncated", pagina.truncada());
		for (var part : pagina.partes()) {
			xml.open("Part").element("PartNumber", part.number()).element("ETag", "\"" + part.sha256() + "\"")
				.element("Size", part.bytes()).close("Part");
		}
		if (pagina.truncada() && !pagina.partes().isEmpty()) {
			xml.element("NextPartNumberMarker", pagina.partes().get(pagina.partes().size() - 1).number());
		}
		xml.close("ListPartsResult");
		return xml(HttpStatus.OK).body(xml.toString());
	}

	/** Uma {@code <Part>} do CompleteMultipartUpload: o número e o ETag que o cliente conferiu. */
	record Parte(int numero, String etag) {
	}

	/**
	 * A lista de partes do CompleteMultipartUpload, na ordem em que o cliente mandou.
	 *
	 * <p>O {@code <ETag>} de cada {@code <Part>} é lido junto do número. O S3 exige que ele seja o da
	 * parte que chegou; antes era descartado, e um cliente que montava o XML com o ETag de outra parte
	 * recebia {@code 200} com bytes que ele não conferiu.</p>
	 *
	 * <p><b>Divergência do S3:</b> aqui, {@code <Part>} sem {@code <ETag>} é aceito e o número basta — é
	 * o que mandam os clientes que não conferem. A AWS responde {@code MalformedXML} nesse caso; a
	 * tolerância é deliberada (não quebra cliente que já funcionava) e o teste dela está em
	 * {@code S3MultipartTests}.</p>
	 *
	 * <p><b>Sem regex:</b> o recorte era uma única expressão com {@code \\s*} guloso ao lado de
	 * {@code [^<]*?} sobre o corpo inteiro — retrocesso sobre entrada do chamador, marcado pelo CodeQL.
	 * A varredura linear abaixo é {@code O(n)} por construção e não depende do tamanho da entrada.</p>
	 */
	private static java.util.List<Parte> partesDoXml(java.io.InputStream body) throws java.io.IOException {
		String texto = new String(body.readAllBytes(), StandardCharsets.UTF_8);
		java.util.List<Parte> partes = new java.util.ArrayList<>();
		int cursor = 0;
		while (true) {
			int abre = texto.indexOf("<Part>", cursor);
			if (abre < 0) {
				break;
			}
			int fecha = texto.indexOf("</Part>", abre);
			if (fecha < 0) {
				break;
			}
			// O trecho de UM `Part`, não o documento: cada `indexOf` começa de pelo menos onde o
			// anterior parou, então o custo é linear no total.
			String trecho = texto.substring(abre + "<Part>".length(), fecha);
			String numero = entre(trecho, "<PartNumber>", "</PartNumber>");
			if (numero == null) {
				throw s3Error(400, "MalformedXML", "Part sem PartNumber.");
			}
			String etag = entre(trecho, "<ETag>", "</ETag>");
			partes.add(new Parte(Integer.parseInt(numero.trim()),
				etag == null ? null : etag.replace("\"", "").trim()));
			cursor = fecha + "</Part>".length();
		}
		if (partes.isEmpty()) {
			throw s3Error(400, "MalformedXML", "CompleteMultipartUpload sem partes.");
		}
		return partes;
	}

	/** O texto entre {@code abre}/{@code fecha}, ou {@code null} se algum faltar. Sem regex. */
	private static String entre(String trecho, String abre, String fecha) {
		int i = trecho.indexOf(abre);
		if (i < 0) {
			return null;
		}
		int f = trecho.indexOf(fecha, i + abre.length());
		if (f < 0) {
			return null;
		}
		return trecho.substring(i + abre.length(), f);
	}

	/**
	 * O objeto que o pedido nomeia: a versão do {@code ?versionId=}, ou a corrente (§4.67).
	 *
	 * <p><b>Antes:</b> {@code ?versionId=} respondia {@code 501 NotImplemented} — o subrecurso estava
	 * na lista de leituras não endereçadas, por não haver como servir outra versão. Mas o gateway
	 * <b>guarda</b> o histórico: com {@code ?versioning Enabled} a versão substituída fica
	 * {@code STORED} e servível, e o {@code ListObjectVersions} a lista com {@code <VersionId>}.
	 * Recusar a leitura de uma versão que está gravada é negar o dado que se tem, e o cliente que
	 * lista o histórico não consegue usá-lo — nem para restaurar, que abre a origem.</p>
	 *
	 * <p><b>Agora:</b> {@code versionId} numérico resolve aquela versão; {@code NoSuchVersion} para
	 * versão que não existe (ou cujo conteúdo foi removido), e {@code 400 InvalidArgument} para valor
	 * que não é número — a mesma régua do marcador do {@code ListObjectVersions}. Sem
	 * {@code versionId}, a corrente.</p>
	 */
	private StorageObject objetoPedido(String bucket, String chave, HttpServletRequest request, String operacao) {
		String versionId = query(request).get("versionId");
		if (versionId == null || versionId.isBlank()) {
			return comVocabularioDoS3(() -> storage.requireDownloadable(bucket, chave, operacao));
		}
		long versao;
		try {
			versao = Long.parseLong(versionId.trim());
		}
		catch (NumberFormatException naoEhNumero) {
			throw s3Error(400, "InvalidArgument",
				"O versionId tem de ser o numero da versao que o ListObjectVersions emitiu.");
		}
		final long pedida = versao;
		return comVocabularioDoS3(() -> storage.requireVersion(bucket, chave, pedida, operacao));
	}

	/**
	 * GetObject: Range/206/416, 304 por If-None-Match ou If-Modified-Since, ETag = digest.
	 *
	 * <p><b>Ordem deliberada:</b> donagem antes da existência. O S3 responde 404 a um bucket que o
	 * chamador não pode ver; aqui a resposta é 403. O motivo é que um 404 para quem tem credencial válida
	 * deixaria o chamador achar que o bucket é dele e o objeto sumiu — e o Storage não tem "bucket
	 * privado": ou é do dono, ou não existe para ele.</p>
	 */
	@GetMapping("/{bucket}/{*key}")
	ResponseEntity<StreamingResponseBody> getObject(@PathVariable String bucket, HttpServletRequest request)
		throws IOException {
		S3Credential credential = authenticate(request, "GET");
		requireBucket(credential, bucket);
		requireBucketDoDono(credential, bucket);
		// `GET ?acl` é a leitura da ACL do objeto, não do objeto: sem esta recusa, a rota servia o
		// arquivo em 200, e o cliente lia "a ACL é o conteúdo do arquivo". Mesma lista do PUT.
		recusarSubrecurso(request, "/" + bucket + "/" + keyOf(request));
		recusarLeituraDeSubrecurso(request, "/" + bucket + "/" + keyOf(request));
		StorageObject object = objetoPedido(bucket, keyOf(request), request, "GET");
		return comVocabularioDoS3(() -> content.respond(object, request.getHeader(HttpHeaders.RANGE),
			request.getHeader(HttpHeaders.IF_NONE_MATCH), request.getHeader(HttpHeaders.IF_MODIFIED_SINCE),
			request.getHeader("If-Range")));
	}

	/** HeadObject: mesmos cabeçalhos do GetObject, sem corpo. */
	@RequestMapping(value = "/{bucket}/{*key}", method = RequestMethod.HEAD)
	ResponseEntity<StreamingResponseBody> headObject(@PathVariable String bucket, HttpServletRequest request)
			throws IOException {
		S3Credential credential = authenticate(request, "GET");
		requireBucket(credential, bucket);
		requireBucketDoDono(credential, bucket);
		// O `HEAD` e o `GET` sem corpo: a mesma pergunta tem a mesma recusa — as duas listas, não só a
		// dos subrecursos de leitura. Sem a primeira, `HEAD ?acl` respondia 200 com o tamanho e o tipo
		// do arquivo, que é a pergunta errada com cara de certa (§4.41).
		recusarSubrecurso(request, "/" + bucket + "/" + keyOf(request));
		recusarLeituraDeSubrecurso(request, "/" + bucket + "/" + keyOf(request));
		StorageObject object = objetoPedido(bucket, keyOf(request), request, "HEAD");
		return comVocabularioDoS3(() -> content.head(object, request.getHeader(HttpHeaders.IF_NONE_MATCH),
			request.getHeader(HttpHeaders.IF_MODIFIED_SINCE)));
	}

	/** DeleteObject: lápide no catálogo. A resposta é 204 mesmo se a chave não existir (igual ao S3). */
	@DeleteMapping("/{bucket}/{*key}")
	ResponseEntity<Void> deleteObject(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "DELETE");
		requireBucket(credential, bucket);
		requireBucketDoDono(credential, bucket);
		String key = keyOf(request);
		// O DELETE não olhava a consulta: `?versionId=1` apagava a chave inteira, e `?tagging`/`?acl`
		// respondiam 204 com o objeto apagado em vez de mexer no subrecurso (§4.45).
		recusarSubrecursoNaoApagavel(request, "/" + bucket + "/" + key);
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
		// O escopo da URL é conferido como o do cabeçalho (§4.56): uma URL assinada para `ec2` não
		// pode abrir o objeto só porque o segredo é o mesmo.
		exigirEscopo(presigned.parsed());
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
		exigirDonoEsperado(request, credential);
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
		// O caminho assinado é o da URL que vai sair: cada segmento codificado como o `uriEncode`
		// aplica (e como o `canonicalUri` do verificador recodifica). Assinar a chave CRUA fazia uma
		// chave com espaço ou acento emitir uma URL cuja assinatura não era a dela — e o portador
		// recebia 403 numa URL recém-emitida.
		StringBuilder pathCodificado = new StringBuilder("/").append(SigV4Verifier.uriEncode(bucket));
		for (String segment : key.split("/")) {
			pathCodificado.append('/').append(SigV4Verifier.uriEncode(segment));
		}
		String path = pathCodificado.toString();

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

	/**
	 * O serviço e a região do escopo SigV4 têm de ser os deste gateway (§4.56).
	 *
	 * <p><b>Antes:</b> o escopo era lido e usado na chave de assinatura, mas nunca conferido — uma
	 * requisição assinada para {@code ec2}, {@code execute-api} ou qualquer região era aceita com
	 * {@code 200}. O segredo é o mesmo, então a assinatura confere; o escopo é a única coisa que diz
	 * para o quê o cliente assinou, e o S3 recusa o que não é {@code s3}.</p>
	 *
	 * <p><b>Agora:</b> serviço tem de ser {@code s3}; região, a da instalação ({@link #REGION}) ou
	 * {@code us-east-1} (a região global do S3, que todo cliente usa por padrão). O resto é
	 * {@code 403 SignatureDoesNotMatch} — o código do S3 para escopo alheio.</p>
	 */
	private static void exigirEscopo(SigV4Verifier.Parsed parsed) {
		String motivo = SigV4Verifier.motivoDeEscopoInvalido(parsed, REGION);
		if (motivo != null) {
			throw s3Error(403, "SignatureDoesNotMatch", motivo);
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
		// O escopo do SigV4 diz PARA O QUÊ o cliente assinou. Antes ele era lido e usado no cálculo sem
		// ser conferido, então uma assinatura para `ec2` em qualquer região lia o objeto (§4.56).
		exigirEscopo(parsed);
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
		exigirDonoEsperado(request, credential);
		exigirRitmo(credential.accessKey());
		return credential;
	}

	/**
	 * {@code x-amz-expected-bucket-owner}: o cliente diz de quem ele acha que é o bucket.
	 *
	 * <p>Existe para o caso em que duas contas têm bucket com o mesmo nome — o SDK manda o dono que
	 * espera e o S3 recusa se o bucket for de outro. Sem isto, o {@code PUT} para um bucket novo (que
	 * nasce do primeiro objeto, sem dono ainda) grava na conta de quem chega primeiro, e o
	 * {@code --expected-bucket-owner} do {@code aws s3api} não protege nada. A conferência é a
	 * <b>última</b> do {@code authenticate}: antes dela, a assinatura e o escopo da credencial já
	 * foram provados, então a resposta não revela nada a quem não assinou.</p>
	 */
	private void exigirDonoEsperado(HttpServletRequest request, S3Credential credential) {
		String esperado = request.getHeader("x-amz-expected-bucket-owner");
		if (esperado == null || esperado.isBlank()) {
			return;
		}
		if (!esperado.trim().equals(credential.ownerUserId())) {
			throw s3Error(403, "AccessDenied",
				"O bucket esperado nao e do dono da credencial (x-amz-expected-bucket-owner).");
		}
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
	 *
	 * <p><b>A lápide conta.</b> A régua é o bucket do dono ({@code bucketsOf}, só vivo) <b>ou</b> o
	 * bucket em que ele tem linha no catálogo, vivo ou lápide ({@code bucketHasObjectsOf}) — a mesma do
	 * {@code DeleteBucket} (§4.15). Só pelos vivos, o dono que apagava a última chave do bucket perdia o
	 * bucket para a própria leitura: o {@code GET} e o {@code HEAD} da lápide respondiam
	 * {@code 403 AccessDenied} ("não é do dono"), em vez do {@code 404 NoSuchKey} que a chave apagada
	 * merece (§4.23). Quem nunca gravou nada num bucket alheio continua barrado: a lápide é de quem a
	 * criou.</p>
	 */
	private void requireBucketDoDono(S3Credential credential, String bucket) {
		if (!catalog.bucketsOf(credential.ownerUserId()).contains(bucket)
				&& !catalog.bucketHasObjectsOf(bucket, credential.ownerUserId())) {
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
		return chaveDeclarada(semBarra.substring(slash + 1));
	}

	/**
	 * A chave como o cliente a declarou: o caminho da URI decodificado.
	 *
	 * <p><b>Antes:</b> a chave era gravada <b>como veio na URI</b>, percent-encoded:
	 * {@code PUT /b/café.txt} guardava {@code caf%C3%A9.txt} e a listagem devolvia essa string. O
	 * nome do objeto no S3 é o declarado — quem compara a {@code <Key>} com o nome local, monta URL
	 * a partir dela ou usa a chave numa cópia por cabeçalho apontava para outro objeto, e não havia
	 * como gravar {@code caf%C3%A9.txt} de propósito.</p>
	 *
	 * <p><b>Agora:</b> {@code %C3%A9} vira {@code é}, {@code %20} vira espaço e {@code +} continua
	 * {@code +} (em caminho, {@code +} não é espaço — diferente do formulário). {@code %25} vira o
	 * {@code %} literal, então a chave que já tem {@code %} é gravável. Percent-encoding inválido é
	 * recusa e a chave fica como veio (§4.52).</p>
	 */
	static String chaveDeclarada(String chaveDaUri) {
		if (chaveDaUri.indexOf('%') < 0) {
			return chaveDaUri;
		}
		try {
			return URLDecoder.decode(chaveDaUri.replace("+", "%2B"), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException percentQuebrado) {
			// `%` solto ou hexadecimal inválido: a URI não é uma chave; responder como veio é a recusa.
			return chaveDaUri;
		}
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

	/**
	 * O {@code Content-MD5} da requisição, já decodificado — ou {@code null} se não veio.
	 *
	 * <p>É o cabeçalho clássico de integridade do S3: o cliente diz o MD5 do corpo e o servidor
	 * confere. Um valor que não é base64 de 16 bytes é {@code 400 InvalidDigest} <b>aqui</b>, antes de
	 * abrir upload — nada é criado. Ver {@code exigirMd5Conferido} para a conferência do conteúdo.</p>
	 */
	private static byte[] md5Declarado(HttpServletRequest request) {
		String declarado = request.getHeader("Content-MD5");
		if (declarado == null || declarado.isBlank()) {
			return null;
		}
		byte[] bytes;
		try {
			bytes = java.util.Base64.getDecoder().decode(declarado.trim());
		}
		catch (IllegalArgumentException naoEDigest) {
			throw s3Error(400, "InvalidDigest", "O Content-MD5 nao e base64 valido.");
		}
		if (bytes.length != 16) {
			throw s3Error(400, "InvalidDigest", "O Content-MD5 precisa ter 16 bytes (MD5 em base64).");
		}
		return bytes;
	}

	/**
	 * O algoritmo de checksum que este gateway confere — o único que a sonda provou.
	 *
	 * <p>Os demais (CRC32, CRC32C, CRC64NVME, SHA-1) ficam de fora por enquanto: aceitá-los sem
	 * conferir seria sucesso silencioso para um digest que ninguém olhou (§4.46).</p>
	 */
	private static final String CHECKSUM_CONFERIDO = "sha256";

	/**
	 * O {@code x-amz-checksum-<algoritmo>} da requisição, ou {@code null} se não veio.
	 *
	 * <p>Algoritmo fora do SHA-256 e valor que não é hexadecimal de 64 dígitos são {@code 400} aqui,
	 * antes de abrir upload. Maiúsculas são normalizadas, como no S3 (§4.46).</p>
	 */
	private static CheckSumDeclarado checksumDeclarado(HttpServletRequest request) {
		String algoritmo = null;
		for (java.util.Enumeration<String> nomes = request.getHeaderNames(); nomes != null && nomes.hasMoreElements();) {
			String nome = nomes.nextElement();
			if (nome == null || !nome.toLowerCase(Locale.ROOT).startsWith("x-amz-checksum-")) {
				continue;
			}
			String qual = nome.substring("x-amz-checksum-".length()).toLowerCase(Locale.ROOT);
			if ("type".equals(qual)) {
				continue;
			}
			if (!CHECKSUM_CONFERIDO.equals(qual)) {
				throw s3Error(400, "NotImplemented",
					"O checksum '" + qual + "' nao e conferido por este gateway; use x-amz-checksum-sha256.");
			}
			algoritmo = qual;
		}
		if (algoritmo == null) {
			return null;
		}
		String valor = request.getHeader("x-amz-checksum-" + algoritmo);
		if (valor == null || !valor.trim().matches("[0-9a-fA-F]{64}")) {
			throw s3Error(400, "InvalidArgument",
				"x-amz-checksum-" + algoritmo + " precisa ser 64 digitos hexadecimais.");
		}
		return new CheckSumDeclarado(algoritmo, valor.trim().toLowerCase(Locale.ROOT));
	}

	/** O checksum declarado pelo cliente e a conferência contra os bytes recebidos. */
	private record CheckSumDeclarado(String algoritmo, String esperado) {

		void conferir(byte[] corpo) {
			String calculado;
			try {
				calculado = java.util.HexFormat.of().formatHex(
					java.security.MessageDigest.getInstance("SHA-256").digest(corpo));
			}
			catch (java.security.NoSuchAlgorithmException semSha) {
				throw s3Error(500, "InternalError", "sha256 nao disponivel para conferir o checksum.");
			}
			if (!calculado.equals(esperado)) {
				// A mesma recusa do `Content-MD5`: o cliente pediu a conferência e ela falhou.
				throw s3Error(400, "BadDigest",
					"O x-amz-checksum-" + algoritmo + " declarado nao confere com o corpo recebido; "
						+ "o objeto nao foi gravado.");
			}
		}
	}

	/**
	 * Confere o {@code Content-MD5} contra os bytes recebidos.
	 *
	 * <p><b>Antes:</b> o cabeçalho era ignorado. Um {@code PUT} com o MD5 de outro corpo respondia
	 * {@code 200} e gravava o objeto, e o cliente que usa o MD5 para pegar corrupção no caminho
	 * (rede, proxy, retentativa) recebia "gravado" para um conteúdo que ele não mandou.</p>
	 *
	 * <p><b>Agora:</b> MD5 que não bate é {@code 400 BadDigest} <b>antes</b> da abertura — nada é
	 * criado nem substituído. O cliente que pede a conferência paga por ela: o corpo vai para memória
	 * nesta operação.</p>
	 */
	private static void exigirMd5Conferido(byte[] corpo, byte[] esperado) {
		byte[] calculado;
		try {
			calculado = java.security.MessageDigest.getInstance("MD5").digest(corpo);
		}
		catch (java.security.NoSuchAlgorithmException semMd5) {
			throw s3Error(500, "InternalError", "MD5 nao disponivel para conferir o Content-MD5.");
		}
		if (!java.util.Arrays.equals(esperado, calculado)) {
			throw s3Error(400, "BadDigest",
				"O Content-MD5 declarado nao confere com o corpo recebido; o objeto nao foi gravado.");
		}
	}

	/** Parâmetro numérico de consulta; ausente ou inválido vale o padrão. */
	private static int inteiroOu(String valor, int padrao) {
		if (valor == null || valor.isBlank()) {
			return padrao;
		}
		try {
			return Integer.parseInt(valor.trim());
		}
		catch (NumberFormatException invalido) {
			return padrao;
		}
	}

	/**
	 * {@code max-keys}: ausente é o padrão de 1000, {@code 0} é a página vazia, e o que não é um
	 * inteiro de zero para cima é {@code 400 InvalidArgument}.
	 *
	 * <p><b>Antes:</b> qualquer entrada inválida — {@code abc}, {@code -1}, {@code 1.5} — virava
	 * 1000 em silêncio, e {@code max-keys=0} também. O cliente recebia dados que não pediu e lia
	 * {@code 200}: {@code aws s3 ls --max-items} com valor errado "funcionava".</p>
	 *
	 * <p><b>Agora:</b> só o vazio é padrão; o resto ou é um inteiro válido (limitado ao teto de
	 * {@value #MAX_KEYS}) ou é recusa — inclusive o zero aceito, que é o que o paginador usa para
	 * sondar.</p>
	 */
	private static int parseMaxKeys(String value) {
		if (value == null || value.isBlank()) {
			return MAX_KEYS;
		}
		int parsed;
		try {
			parsed = Integer.parseInt(value.trim());
		}
		catch (NumberFormatException invalido) {
			throw s3Error(400, "InvalidArgument",
				"max-keys precisa ser um inteiro de 0 em diante; veio \'" + value + "\'.");
		}
		if (parsed < 0) {
			throw s3Error(400, "InvalidArgument", "max-keys nao pode ser negativo; veio " + parsed + ".");
		}
		return Math.min(parsed, MAX_KEYS);
	}

	private static ResponseEntity.BodyBuilder xml(HttpStatus status) {
		return ResponseEntity.status(status).header(HttpHeaders.CONTENT_TYPE, "application/xml; charset=utf-8");
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
		if (recusado.status() == 404 && "STORAGE_NOT_FOUND".equals(recusado.code())) {
			// O código do S3 para chave ausente é o que o SDK procura para distinguir "não existe"
			// de "sem permissão". O interno (`STORAGE_NOT_FOUND`) vazava para o cliente.
			return s3Error(404, "NoSuchKey", recusado.getMessage());
		}
		if ("STORAGE_PLACEMENT_MISSING".equals(recusado.code())) {
			// O catálogo diz que existe e não há cópia: para o cliente do S3 é um conflito
			// transitório do lado do servidor, não um erro de pedido — e ele sabe tentar de novo.
			return s3Error(409, "InternalError", "O objeto esta confirmado no catalogo mas sem copia"
				+ " registrada em no nenhum; tente de novo.");
		}
		if ("STORAGE_REPLICA_ELSEWHERE".equals(recusado.code())) {
			// A cópia existe, mas em outro nó: é o caso "tenta de novo em instantes" do S3. O XML do
			// `SlowDown` é o `ClientException` literal que o `S3Exception.retryable` do SDK repete; o
			// `409` do control plane (que usa o digest para designar o nó) não serve ao cliente do S3 —
			// e, sem esta tradução, ele saía como JSON do Énóis. O `Retry-After` diz quando voltar.
			return s3Error(503, "SlowDown", "A copia deste objeto esta em outro no; tente de novo.")
				.withRetryAfter(1);
		}
		// Qualquer outra recusa que nasceu nos serviços do Storage vira XML: o código interno do
		// Énóis é legível o bastante (`NoSuchUpload`, `InvalidArgument`, `MalformedXML`, `InvalidPart`
		// são os do S3) e o `Code` é o que o SDK lê. Antes, só os dois casos acima eram traduzidos e
		// o resto saía como JSON do Control Plane — corpo que o SDK não sabe deserializar.
		//
		// A validação de nome de bucket (§4.55) é exceção à regra de repassar o código interno: o
		// `STORAGE_BUCKET_INVALID` do Énóis não é código do S3, e o SDK que procura `InvalidBucketName`
		// para mostrar a mensagem certa não o reconhece. A mensagem (que diz qual regra violou) vai
		// inteira.
		if ("STORAGE_BUCKET_INVALID".equals(recusado.code())) {
			return s3Error(recusado.status(), "InvalidBucketName", recusado.getMessage());
		}
		return s3Error(recusado.status(), recusado.code(), recusado.getMessage());
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
	/**
	 * CreateBucket: {@code PUT /{bucket}}, o `aws s3 mb` e o `rclone mkdir`.
	 *
	 * <p><b>Antes:</b> a rota caía no {@code PutObject} e respondia
	 * {@code 400 InvalidRequest "Informe o bucket e a chave"}. O primeiro comando de qualquer fluxo de
	 * upload falhava, e o cliente que cria o bucket antes de gravá-lo (o jeito normal do S3) não tinha
	 * caminho.</p>
	 *
	 * <p><b>Agora:</b> registra o bucket no catálogo para o dono da credencial. Criar de novo o mesmo
	 * bucket do mesmo dono é <b>200</b> idempotente ({@code BucketAlreadyOwnedByYou} é sucesso no S3);
	 * nome que já é de outra conta é {@code 409 BucketAlreadyExists} e não muda o dono.</p>
	 *
	 * <p>O Storage não tem tabela de donos de bucket: o dono vem dos objetos ({@code bucketsOf}), e a
	 * linha em {@code network_storage_buckets} leva o criador em {@code updated_by} — é o que permite
	 * responder 409 antes de o bucket ter o primeiro objeto.</p>
	 */
	@RequestMapping(value = "/{bucket}", method = RequestMethod.PUT)
	ResponseEntity<Void> createBucket(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "PUT");
		requireBucket(credential, bucket);
		String donoAtual = donoDoBucketRegistrado(bucket);
		if (donoAtual != null && !donoAtual.equals(credential.ownerUserId())) {
			throw s3Error(409, "BucketAlreadyExists",
				"O bucket ja existe em outra conta.");
		}
		if (donoAtual == null) {
			// O nome é conferido aqui — e não só no primeiro objeto (§4.55): `PUT /com_underline`
			// respondia 200 e registrava o bucket com um nome que o S3 recusa como host.
			try {
				storage.putBucketPolicy(credential.ownerUserId(), bucket, false, null, null);
			}
			catch (ApiException recusado) {
				throw noVocabularioDoS3(recusado);
			}
		}
		return ResponseEntity.ok().build();
	}

	/** HeadBucket: {@code HEAD /{bucket}} — o SDK confirma o bucket antes de gravar. */
	@RequestMapping(value = "/{bucket}", method = RequestMethod.HEAD)
	ResponseEntity<Void> headBucket(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "LIST");
		requireBucket(credential, bucket);
		// 404 para o que não é do dono, como o S3: o HEAD não revela a existência de bucket alheio.
		boolean nosso = credential.ownerUserId().equals(donoDoBucketRegistrado(bucket));
		return nosso ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
	}

	/** GetBucketLocation: {@code GET /{bucket}?location} — a região do gateway. */
	@GetMapping(value = "/{bucket}", params = "location")
	ResponseEntity<String> bucketLocation(@PathVariable String bucket, HttpServletRequest request) {
		S3Credential credential = authenticate(request, "LIST");
		requireBucket(credential, bucket);
		// O criador registrado conta como dono mesmo antes do primeiro objeto (o bucket nasce no
		// CreateBucket); depois disso vale a mesma régua do resto do gateway.
		if (!credential.ownerUserId().equals(donoDoBucketRegistrado(bucket))) {
			requireBucketDoDono(credential, bucket);
		}
		S3Xml xml = new S3Xml();
		xml.root("LocationConstraint").text(REGION);
		return xml(HttpStatus.OK).body(xml.toString());
	}

	/**
	 * O dono registrado do bucket: o <b>criador</b>, gravado em {@code owner_user_id} no
	 * {@code CreateBucket} e nunca reescrito — quem configura a política depois não vira dono. Sem
	 * criador registrado (bucket anterior à coluna), o dono do primeiro objeto vivo: o bucket que
	 * nasceu do primeiro upload, de quem só sobe objeto sem {@code CreateBucket}.
	 */
	private String donoDoBucketRegistrado(String bucket) {
		return catalog.bucketPolicy(bucket).map(StorageBucketPolicy::ownerUserId)
			.filter(criador -> criador != null && !criador.isBlank())
			.or(() -> catalog.ownerDoBucket(bucket))
			.orElse(null);
	}

}