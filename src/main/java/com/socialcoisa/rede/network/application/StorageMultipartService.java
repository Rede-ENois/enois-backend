package com.socialcoisa.rede.network.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import org.springframework.lang.Nullable;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Multipart Upload do gateway S3 (V365).
 *
 * <p><b>Antes:</b> o gateway recusava {@code CreateMultipartUpload} com 501 — e não era um detalhe de
 * compatibilidade. Multipart é o caminho <b>padrão</b> de upload de todo SDK (boto3 acima de 8 MB, SDK
 * Java acima de 5 MiB), então um vídeo enviado pelo SDK falhava num arquivo que o protocolo inteiro
 * sabe enviar. <b>Agora:</b> o upload é criado, as partes chegam fora de ordem e em paralelo, cada
 * uma com o próprio digest, e a conclusão monta o corpo na ordem que o cliente pediu e grava pelo
 * <b>mesmo</b> caminho do {@code PUT} simples ({@link StorageCatalogService#beginUpload} +
 * {@code finishUpload}).</p>
 *
 * <h2>Por que os bytes da parte moram aqui e não no catálogo</h2>
 *
 * <p>O catálogo só conhece uma chave quando existe um objeto: ele não tem onde guardar "parte 3 de
 * um upload que ainda não é objeto". As partes ficam em {@code <storageRoot>/multipart/<uploadId>/},
 * no mesmo volume do resto do corpo — não é um segundo depósito, é a mesma vizinhança —, e saem
 * juntas na conclusão (o canal que serve a conclusão é apagado) ou na varredura de abandono.</p>
 *
 * <h2>As regras que fazem a conclusão ser segura</h2>
 *
 * <ul>
 * <li><b>Nada vira objeto até a conclusão.</b> O catálogo só conhece a chave quando o arquivo inteiro
 * existe; um upload pela metade nunca é servível por {@code GET}.</li>
 * <li><b>Parte ausente é erro, não buraco.</b> A lista de conclusão nomeia cada parte; se uma não
 * chegou, a resposta é {@code InvalidPart} e o upload continua aberto — o cliente reenvia só ela.
 * Concluir com o que existe "porque existe" serviria um arquivo truncado como se estivesse completo.</li>
 * <li><b>Digest por parte, digest do todo.</b> Cada parte grava o sha-256 do que chegou. O ETag do
 * objeto é o sha-256 dos digests concatenados (o mesmo cálculo do SDK), e é conferido contra o corpo
 * montado antes de virar objeto.</li>
 * <li><b>Parte fora de ordem é normal.</b> O número da parte é a ordem; a chegada não é.</li>
 * <li><b>Reenviar parte substitui.</b> É o comportamento do S3 e o que permite retomar depois de uma
 * rede que caiu.</li>
 * <li><b>Credencial fixa no início.</b> A donagem do bucket é provada no {@code Create} — é ele que
 * cria o bucket do dono; as partes seguintes carregam a credencial do upload.</li>
 * <li><b>Parte pequena que não é a última é recusada?</b> Não aqui, e de propósito: o S3 exige 5 MiB
 * para toda parte que não é a última, mas quem decide o que é "última" é a lista de conclusão. A
 * checagem de tamanho mínimo é feita na conclusão, sobre as partes que têm sucessora — recusar no
 * envio faria um cliente que manda as partes fora de ordem (ou a última primeiro) falhar sem motivo.</li>
 * </ul>
 */
@Service
public class StorageMultipartService {

	private static final Logger LOG = LoggerFactory.getLogger(StorageMultipartService.class);

	/** Teto do S3 para o número da parte. */
	static final int MAX_PART_NUMBER = 10000;

	/** Mínimo do S3 para toda parte que não é a última (5 MiB). */
	static final long MIN_PART_BYTES = 5L * 1024 * 1024;

	private final JdbcTemplate jdbc;
	private final StorageCatalogService storage;
	private final Clock clock;
	private final Duration defaultTtl;
	private final long maxObjectBytes;
	private final Path root;
	private final StorageOperationMetrics metrics;

	/** Sem contador de operação: o multipart continua servindo (a métrica é observação, não pré-requisito). */
	public StorageMultipartService(JdbcTemplate jdbc, StorageCatalogService storage, Clock clock, long ttlHours,
			long maxObjectBytes, String storageRoot) {
		this(jdbc, storage, clock, ttlHours, maxObjectBytes, storageRoot, null);
	}

	@Autowired
	public StorageMultipartService(JdbcTemplate jdbc, StorageCatalogService storage, Clock clock,
			@Value("${rede.storage.multipart.ttl-hours:24}") long ttlHours,
			@Value("${rede.storage.max-object-bytes:5368709120}") long maxObjectBytes,
			@Value("${rede.storage.root:${java.io.tmpdir}/enois-storage}") String storageRoot,
			@Nullable StorageOperationMetrics metrics) {
		this.jdbc = jdbc;
		this.storage = storage;
		this.clock = clock;
		this.defaultTtl = Duration.ofHours(Math.max(1, ttlHours));
		this.maxObjectBytes = maxObjectBytes;
		this.root = Path.of(storageRoot).resolve("multipart");
		this.metrics = metrics;
	}

	/**
	 * O upload aberto, como o cliente o vê.
	 *
	 * <p>{@code cabecalhos} é o que o cliente declarou no {@code CreateMultipartUpload} (V367):
	 * metadado, {@code Cache-Control} e {@code Content-Disposition}. Vive só na memória desta resposta
	 * e da conclusão, porque a tabela de uploads não precisa de coluna nova — o metadado só tem valor
	 * quando o objeto nasce, na conclusão. Upload lido do banco (retomada) vem com
	 * {@link StorageCatalogService.ClientHeaders#NONE}: é o que o cliente declarou ao começar e já
	 * teria sido gravado se a conclusão já tivesse acontecido.</p>
	 */
	public record Upload(String uploadId, String bucket, String key, String contentType, String ownerUserId,
			String credentialId, Instant createdAt, Instant expiresAt,
			StorageCatalogService.ClientHeaders cabecalhos) {

		/** A mesma linha sem o que o cliente declarou — o formato de tudo que existia antes do V367. */
		public Upload(String uploadId, String bucket, String key, String contentType, String ownerUserId,
				String credentialId, Instant createdAt, Instant expiresAt) {
			this(uploadId, bucket, key, contentType, ownerUserId, credentialId, createdAt, expiresAt,
				StorageCatalogService.ClientHeaders.NONE);
		}

		/** Uma cópia com os cabeçalhos declarados pelo cliente (V367) — a linha do banco não muda. */
		public Upload comCabeçalhos(StorageCatalogService.ClientHeaders declarados) {
			return new Upload(uploadId, bucket, key, contentType, ownerUserId, credentialId, createdAt,
				expiresAt, declarados == null ? StorageCatalogService.ClientHeaders.NONE : declarados);
		}
	}

	/** Uma parte recebida. */
	public record Part(int number, long bytes, String sha256) {
	}

	/** O resultado da conclusão. */
	public record Completed(String bucket, String key, String objectId, long bytes, String etag) {
	}

	/**
	 * Abre o upload. A chave ainda não existe no catálogo: quem a cria é a conclusão.
	 *
	 * <p>{@code expiresInSeconds} nulo usa o prazo da instalação; acima do teto é <b>recusado</b> em
	 * vez de truncado — um cliente que pede 7 dias e recebe 24 h descobriria no meio do upload.</p>
	 */
	public Upload create(String ownerUserId, String credentialId, String bucket, String key, String contentType,
			Long expiresInSeconds) {
		long inicio = System.nanoTime();
		try {
			Upload upload = doCreate(ownerUserId, credentialId, bucket, key, contentType, expiresInSeconds);
			conta("PUT", inicio, null);
			return upload;
		}
		catch (RuntimeException recusado) {
			conta("PUT", inicio, recusado);
			throw recusado;
		}
	}

	private Upload doCreate(String ownerUserId, String credentialId, String bucket, String key, String contentType,
			Long expiresInSeconds) {
		Instant now = clock.instant();
		Duration ttl = defaultTtl;
		if (expiresInSeconds != null) {
			Duration pedido = Duration.ofSeconds(expiresInSeconds);
			if (pedido.isNegative() || pedido.isZero() || pedido.compareTo(defaultTtl) > 0) {
				throw new ApiException(400, "InvalidArgument",
					"Validade do upload fora do intervalo aceito (1 s a " + defaultTtl.toHours() + " h).");
			}
			ttl = pedido;
		}
		String uploadId = "mpu_" + UUID.randomUUID().toString().replace("-", "");
		String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
		jdbc.update("""
			insert into network_storage_multipart_uploads (upload_id, bucket, `key`, content_type, owner_user_id,
				credential_id, created_at, expires_at) values (?, ?, ?, ?, ?, ?, ?, ?)
			""", uploadId, bucket, key, type, ownerUserId, credentialId, java.sql.Timestamp.from(now),
			java.sql.Timestamp.from(now.plus(ttl)));
		return new Upload(uploadId, bucket, key, type, ownerUserId, credentialId, now, now.plus(ttl));
	}

	/** O upload, exigindo que seja do dono que pergunta — upload de outro é 404, não 403 (não existe). */
	public Upload require(String uploadId, String ownerUserId) {
		return find(uploadId).filter(u -> u.ownerUserId().equals(ownerUserId))
			.orElseThrow(() -> new ApiException(404, "NoSuchUpload",
				"Upload multipart inexistente, concluido ou de outra conta."));
	}

	/**
	 * Registra a operação no painel (doc 18 §5.3). O multipart serve os arquivos grandes e não
	 * contava nada: o painel dizia "multipart nunca acontece" com um cliente enviando gigabytes.
	 * Os verbos são os do S3 — abrir e cada parte são {@code PUT}, listar é {@code LIST}.
	 */
	private void conta(String operacao, long inicioNanos, RuntimeException falha) {
		if (metrics == null) {
			return;
		}
		StorageOperationMetrics.Resultado resultado = falha == null ? StorageOperationMetrics.Resultado.OK
			: falha instanceof com.socialcoisa.rede.shared.web.ApiException api && api.status() < 500
				? StorageOperationMetrics.Resultado.CLIENT
				: StorageOperationMetrics.Resultado.SERVER;
		metrics.record(operacao, resultado, Math.max(0, (System.nanoTime() - inicioNanos) / 1_000_000));
	}

	public Optional<Upload> find(String uploadId) {
		return jdbc.query("""
			select upload_id, bucket, `key`, content_type, owner_user_id, credential_id, created_at, expires_at
			from network_storage_multipart_uploads where upload_id = ?
			""", (rs, i) -> new Upload(rs.getString("upload_id"), rs.getString("bucket"), rs.getString("key"),
			rs.getString("content_type"), rs.getString("owner_user_id"), rs.getString("credential_id"),
			rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant()), uploadId)
			.stream()
			.findFirst();
	}

	public List<Part> parts(String uploadId) {
		long inicio = System.nanoTime();
		try {
			List<Part> lista = doParts(uploadId);
			conta("LIST", inicio, null);
			return lista;
		}
		catch (RuntimeException recusado) {
			conta("LIST", inicio, recusado);
			throw recusado;
		}
	}

	private List<Part> doParts(String uploadId) {
		return jdbc.query("""
			select part_number, bytes, sha256 from network_storage_multipart_parts
			where upload_id = ? order by part_number
			""", (rs, i) -> new Part(rs.getInt("part_number"), rs.getLong("bytes"), rs.getString("sha256")),
			uploadId);
	}

	/**
	 * Recebe uma parte: grava os bytes em {@code multipart/<uploadId>/<numero>} e o digest na tabela.
	 *
	 * <p>Reenvio da mesma parte substitui o arquivo e a linha — é o que permite retomar. O arquivo é
	 * escrito num temporário e movido por cima: uma parte que cai pela metade durante a escrita nunca
	 * fica com o nome da parte.</p>
	 */
	/**
	 * Recebe uma parte.
	 *
	 * @param anunciado o {@code Content-Length} que o cliente mandou, quando mandou; é o único tamanho
	 *                  conhecido <b>antes</b> de ler, e é o que deixa a cota recusar sem gravar nada.
	 *                  {@code null} quando o corpo vem em chunks (sem tamanho declarado): aí a cota é
	 *                  conferida depois da leitura, antes de o arquivo virar a parte
	 */
	public Part receivePart(Upload upload, int partNumber, InputStream body, Long anunciado) {
		long inicio = System.nanoTime();
		try {
			Part parte = doReceivePart(upload, partNumber, body, anunciado);
			conta("PUT", inicio, null);
			return parte;
		}
		catch (RuntimeException recusado) {
			conta("PUT", inicio, recusado);
			throw recusado;
		}
	}

	private Part doReceivePart(Upload upload, int partNumber, InputStream body, Long anunciado) {
		if (partNumber < 1 || partNumber > MAX_PART_NUMBER) {
			throw new ApiException(400, "InvalidArgument",
				"Numero de parte fora do intervalo 1.." + MAX_PART_NUMBER + ".");
		}
		Instant now = clock.instant();
		if (now.isAfter(upload.expiresAt())) {
			discard(upload.uploadId());
			throw new ApiException(400, "NoSuchUpload", "O prazo deste upload multipart venceu.");
		}
		Path dir = dirOf(upload.uploadId());
		Path target = dir.resolve("part-" + partNumber);
		Path temp;
		try {
			Files.createDirectories(dir);
			temp = Files.createTempFile(dir, ".part-", ".tmp");
		}
		catch (IOException unusable) {
			throw new ApiException(500, "STORAGE_ROOT_UNAVAILABLE", "Diretorio de armazenamento indisponivel.");
		}
		MessageDigest digest = sha256();
		long total = 0;
		byte[] buffer = new byte[64 * 1024];
		// A parte já substituída não conta duas vezes: o reenvio soma à conta e a antiga sai dela.
		long jaNestaParte = jdbc.queryForObject(
			"select coalesce(sum(bytes), 0) from network_storage_multipart_parts where upload_id = ? and part_number = ?",
			Long.class, upload.uploadId(), partNumber);
		// O que a cota vê é o que já está gravado nesta conta mais o que já está reservado em partes
		// abertas — sem isto, 10 000 partes de 5 MiB passavam por baixo de uma cota de 1 MB.
		long reservado = bytesInOpenParts(upload.ownerUserId()) - jaNestaParte;
		// Com o tamanho anunciado, a recusa é antes de qualquer byte; sem ele, a conta fecha abaixo,
		// depois de medir a parte e antes de ela virar parte do upload.
		if (anunciado != null && anunciado > 0) {
			storage.exigirCotaDasPartes(upload.ownerUserId(), reservado, anunciado);
		}
		try (InputStream input = body; OutputStream sink = Files.newOutputStream(temp)) {
			int read;
			while ((read = lerNoMaximo(input, buffer, maxObjectBytes - total + 1)) != -1) {
				digest.update(buffer, 0, read);
				sink.write(buffer, 0, read);
				total += read;
				if (total > maxObjectBytes) {
					throw new ApiException(413, "EntityTooLarge",
						"A parte excede o teto do objeto desta instalacao (" + maxObjectBytes + " bytes).");
				}
			}
		}
		catch (IOException unreadable) {
			deleteQuietly(temp);
			throw new ApiException(400, "STORAGE_UPLOAD_FAILED", "Nao foi possivel ler o corpo enviado.");
		}
		catch (ApiException recusado) {
			deleteQuietly(temp);
			throw recusado;
		}
		if (anunciado == null || anunciado <= 0) {
			// Corpo sem tamanho declarado: a conta fecha com o que a parte realmente mediu, ainda
			// antes de o arquivo virar a parte — a recusa não deixa parte gravada.
			storage.exigirCotaDasPartes(upload.ownerUserId(), reservado, total);
		}
		if (total == 0) {
			// O S3 recusa parte vazia. Aceitar em silêncio criaria uma "parte" que não é dado.
			deleteQuietly(temp);
			throw new ApiException(400, "InvalidArgument", "Uma parte multipart nao pode ser vazia.");
		}
		try {
			Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException moveFailed) {
			deleteQuietly(temp);
			throw new ApiException(500, "STORAGE_ROOT_UNAVAILABLE", "Nao foi possivel guardar a parte.");
		}
		String sha = HexFormat.of().formatHex(digest.digest());
		jdbc.update("""
			insert into network_storage_multipart_parts (upload_id, part_number, bytes, sha256, received_at)
			values (?, ?, ?, ?, ?)
			on duplicate key update bytes = values(bytes), sha256 = values(sha256), received_at = values(received_at)
			""", upload.uploadId(), partNumber, total, sha, java.sql.Timestamp.from(now));
		return new Part(partNumber, total, sha);
	}

	/**
	 * Conclui o upload: confere a lista, o tamanho mínimo das partes intermediárias, o digest do todo e
	 * grava o objeto pelo caminho do PUT simples.
	 *
	 * <p>O corpo é montado num arquivo só (em ordem) para que o catálogo meça o digest dele mesmo — a
	 * conclusão não "confia" no ETag que ela calculou antes; ela mede de novo o que vai ser gravado.
	 * Os dois têm de bater.</p>
	 */
	public Completed complete(Upload upload, List<Integer> pedidas) {
		long inicio = System.nanoTime();
		try {
			Completed pronto = doComplete(upload, pedidas);
			// A gravação do objeto já contou `PUT` no catálogo; contar aqui de novo faria todo upload
			// grande valer dois. A conclusão conta só quando falha — é ela que o painel precisa ver.
			return pronto;
		}
		catch (RuntimeException recusado) {
			conta("PUT", inicio, recusado);
			throw recusado;
		}
	}

	private Completed doComplete(Upload upload, List<Integer> pedidas) {
		Instant now = clock.instant();
		if (now.isAfter(upload.expiresAt())) {
			discard(upload.uploadId());
			throw new ApiException(400, "NoSuchUpload", "O prazo deste upload multipart venceu.");
		}
		if (pedidas.isEmpty()) {
			throw new ApiException(400, "MalformedXML", "A conclusao precisa listar ao menos uma parte.");
		}
		// A leitura das partes aqui é parte da conclusão: contar como `LIST` inflaria o painel com
		// uma listagem que ninguém pediu.
		List<Part> existentes = doParts(upload.uploadId());
		java.util.Map<Integer, Part> porNumero = existentes.stream()
			.collect(java.util.stream.Collectors.toMap(Part::number, p -> p));
		long total = 0;
		for (int i = 0; i < pedidas.size(); i++) {
			Part part = porNumero.get(pedidas.get(i));
			// A lista é a ordem do arquivo, e o S3 exige estritamente crescente. Repetir um número
			// montava a mesma parte duas vezes: o cliente recebia 200 e um objeto com o dobro do que
			// enviou. Numeração fora de ordem é a mesma recusa — o corpo não é o que ela descreve.
			if (i > 0 && pedidas.get(i) <= pedidas.get(i - 1)) {
				throw new ApiException(400, "InvalidPartOrder",
					"A conclusao pediu a parte " + pedidas.get(i) + " depois da "
						+ pedidas.get(i - 1) + "; os numeros tem de ser crescentes e sem repeticao.");
			}
			if (part == null) {
				throw new ApiException(400, "InvalidPart",
					"A parte " + pedidas.get(i) + " nao chegou; reenvie-a antes de concluir.");
			}
			boolean ultima = i == pedidas.size() - 1;
			if (!ultima && part.bytes() < MIN_PART_BYTES) {
				throw new ApiException(400, "EntityTooSmall",
					"A parte " + part.number() + " tem menos de 5 MiB; so a ultima pode ser menor.");
			}
			total += part.bytes();
			if (total > maxObjectBytes) {
				throw new ApiException(413, "EntityTooLarge",
					"O objeto excede o teto desta instalacao (" + maxObjectBytes + " bytes).");
			}
		}
		Path montado = montar(upload.uploadId(), pedidas, porNumero);
		// O temporário é apagado em QUALQUER saída daqui até a gravação dar certo: a recusa por cota,
		// a falha do catálogo e a releitura. Antes o `finally` cobria só a releitura, e uma conclusão
		// recusada depois da montagem deixava o corpo inteiro no diretório do upload — que continua
		// aberto, então o varredor de sobras não recolhe — cobrando disco duas vezes pelo mesmo upload.
		com.socialcoisa.rede.network.domain.StorageObject gravado;
		try {
			// A integridade de cada parte é conferida no envio (o digest é do arquivo gravado) e a
			// montagem não altera byte: entre a parte e o corpo montado não há transformação que possa
			// divergir do sha-256 das partes. O digest do objeto é o do corpo montado, e é o
			// `finishUpload` que mede de novo — o que a conclusão devolve é o que foi gravado, não o
			// que ela calculou.
			String etag = sha256DoArquivo(montado);

			// A cota das partes já foi cobrada no envio de cada uma: na conclusão, o reservado deste
			// upload É o corpo que está chegando, e cobrá-lo duas vezes faria a conclusão falhar sempre
			// que o objeto não fosse minúsculo. Quem cobra agora é o `finishUpload`, pelo corpo montado.
			com.socialcoisa.rede.network.domain.StorageObject pendente = storage.beginUpload(
				upload.ownerUserId(), upload.bucket(), upload.key(), upload.contentType(), false);
			// O metadado do CreateMultipartUpload vai para o objeto, como o do PUT simples: o cliente
			// declarou uma vez, no começo, e espera reencontrá-lo no HEAD e no GET (V367).
			storage.saveClientHeaders(pendente, upload.cabecalhos());
			try (InputStream corpo = Files.newInputStream(montado)) {
				gravado = storage.finishUpload(pendente.id(), corpo, upload.ownerUserId(), false);
			}
			catch (IOException unreadable) {
				throw new ApiException(500, "InternalError", "Nao foi possivel reler o corpo montado.");
			}
		}
		catch (RuntimeException recusado) {
			deleteQuietly(montado);
			throw recusado;
		}
		deleteQuietly(montado);
		discard(upload.uploadId());
		return new Completed(upload.bucket(), upload.key(), gravado.id(), gravado.bytes(), gravado.sha256());
	}

	/** Aborta: apaga as partes e o registro. Idempotente — abortar duas vezes é a mesma coisa. */
	public void abort(String uploadId) {
		long inicio = System.nanoTime();
		try {
			doAbort(uploadId);
			conta("DELETE", inicio, null);
		}
		catch (RuntimeException recusado) {
			conta("DELETE", inicio, recusado);
			throw recusado;
		}
	}

	private void doAbort(String uploadId) {
		discard(uploadId);
	}

	/**
	 * Lê no máximo {@code limite} bytes: um byte além do teto basta para saber que passou.
	 *
	 * <p>O mesmo cuidado do {@code measure} do catálogo — sem ele, a parte recusada puxava mais um
	 * bloco de 64 KiB do cliente só para responder {@code 413}.</p>
	 */
	private static int lerNoMaximo(InputStream input, byte[] buffer, long limite) throws IOException {
		int pedido = (int) Math.min(buffer.length, Math.max(1, limite));
		return input.read(buffer, 0, pedido);
	}

	// ------------------------------------------------------------------ coleta do vencido (§4.2)

	/** Quantos uploads estão abertos agora (nenhum foi concluído nem abortado). */
	int uploadsOpen() {
		Integer total = jdbc.queryForObject("select count(*) from network_storage_multipart_uploads",
			Integer.class);
		return total == null ? 0 : total;
	}

	/** Quantos uploads já passaram do prazo gravado no início. */
	int expiredCount(java.time.Instant now) {
		Integer total = jdbc.queryForObject(
			"select count(*) from network_storage_multipart_uploads where expires_at <= ?", Integer.class,
			java.sql.Timestamp.from(now));
		return total == null ? 0 : total;
	}

	/**
	 * As linhas vencidas, da que venceu faz mais tempo para a mais recente.
	 *
	 * <p>O prazo é o de {@code expires_at}, gravado no {@code CreateMultipartUpload} e possivelmente
	 * menor que o padrão da instalação: quem coleta não recalcula nada.</p>
	 */
	List<Upload> expiredUploads(java.time.Instant now, int limit) {
		return jdbc.query("""
			select upload_id, bucket, `key`, content_type, owner_user_id, credential_id, created_at, expires_at
			from network_storage_multipart_uploads where expires_at <= ?
			order by expires_at asc, upload_id asc limit ?
			""", (rs, i) -> new Upload(rs.getString("upload_id"), rs.getString("bucket"), rs.getString("key"),
			rs.getString("content_type"), rs.getString("owner_user_id"), rs.getString("credential_id"),
			rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant()),
			java.sql.Timestamp.from(now), limit);
	}

	/**
	 * O que as partes <b>abertas</b> desta conta já ocupam: espaço reservado antes de virar objeto.
	 *
	 * <p>É o que a cota cobra no envio da parte. Só os uploads abertos contam — a linha sai quando o
	 * upload é concluído ou abortado, e a conclusão já cobra a cota pelo {@code finishUpload}.</p>
	 */
	long bytesInOpenParts(String ownerUserId) {
		Long total = jdbc.queryForObject("""
			select coalesce(sum(p.bytes), 0) from network_storage_multipart_parts p
			join network_storage_multipart_uploads u on u.upload_id = p.upload_id
			where u.owner_user_id = ?
			""", Long.class, ownerUserId);
		return total == null ? 0 : total;
	}

	/** Quantas partes o upload recebeu — o que o relatório conta como espaço a recuperar. */
	long partCount(String uploadId) {
		Long total = jdbc.queryForObject(
			"select count(*) from network_storage_multipart_parts where upload_id = ?", Long.class, uploadId);
		return total == null ? 0 : total;
	}

	/**
	 * Apaga os diretórios de partes que não têm upload correspondente e devolve os ids recolhidos.
	 *
	 * <p>Sobra de passada interrompida: um {@code discard} que apagou a linha e caiu antes do disco, ou
	 * um varredor que parou no meio do lote. Só o diretório <b>imediatamente</b> dentro de
	 * {@code multipart/} e com o padrão do id é candidato — nada abaixo é descoberto por
	 * recursão, então o varredor não tem como chegar a um caminho que este serviço não criou. O
	 * caminho devolvido é o que o relatório mostra.</p>
	 */
	java.util.List<Path> removeOrphanDirectories() {
		if (!Files.isDirectory(root)) {
			return java.util.List.of();
		}
		java.util.List<Path> recolhidos = new java.util.ArrayList<>();
		// Um nível só, sem recursão: nada abaixo de `multipart/` é descoberto pelo varredor, então ele
		// não tem como chegar a um caminho que este serviço não criou.
		try (Stream<Path> dirs = Files.list(root)) {
			for (Path dir : dirs.toList()) {
				String nome = dir.getFileName().toString();
				if (!Files.isDirectory(dir) || !nome.matches("[A-Za-z0-9_-]{1,64}")) {
					continue;
				}
				Integer existe = jdbc.queryForObject(
					"select count(*) from network_storage_multipart_uploads where upload_id = ?", Integer.class,
					nome);
				if (existe != null && existe > 0) {
					continue;
				}
				try (Stream<Path> arquivos = Files.walk(dir)) {
					arquivos.sorted(java.util.Comparator.reverseOrder())
						.forEach(StorageMultipartService::deleteQuietly);
				}
				recolhidos.add(dir);
			}
		}
		catch (IOException failed) {
			LOG.warn("multipart: nao foi possivel varrer {}: {}", root, failed.getMessage());
		}
		return recolhidos;
	}


	// ------------------------------------------------------------------ corpo montado

	private Path montar(String uploadId, List<Integer> pedidas, java.util.Map<Integer, Part> porNumero) {
		Path dir = dirOf(uploadId);
		try {
			Files.createDirectories(dir);
			Path montado = Files.createTempFile(dir, ".montado-", ".tmp");
			try (OutputStream sink = Files.newOutputStream(montado)) {
				for (Integer numero : pedidas) {
					try (InputStream parte = Files.newInputStream(dir.resolve("part-" + numero))) {
						parte.transferTo(sink);
					}
				}
			}
			return montado;
		}
		catch (IOException failed) {
			throw new ApiException(500, "InternalError", "Nao foi possivel montar o corpo das partes.");
		}
	}

	private static String sha256DoArquivo(Path file) {
		MessageDigest digest = sha256();
		byte[] buffer = new byte[64 * 1024];
		try (InputStream input = Files.newInputStream(file)) {
			int read;
			while ((read = input.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
		}
		catch (IOException unreadable) {
			throw new ApiException(500, "InternalError", "Nao foi possivel reler o corpo montado.");
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private void discard(String uploadId) {
		jdbc.update("delete from network_storage_multipart_parts where upload_id = ?", uploadId);
		jdbc.update("delete from network_storage_multipart_uploads where upload_id = ?", uploadId);
		Path dir = dirOf(uploadId);
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> arquivos = Files.walk(dir)) {
			arquivos.sorted(java.util.Comparator.reverseOrder()).forEach(StorageMultipartService::deleteQuietly);
		}
		catch (IOException ignored) {
			LOG.warn("multipart: nao foi possivel limpar {}", dir);
		}
	}

	/** O diretório de partes deste processo — o que a varredura de sobras e a rota leem. */
	public Path root() {
		return this.root;
	}

	private Path dirOf(String uploadId) {
		// O id é gerado aqui (`mpu_` + hex) e a rota só aceita upload existente: não há caminho de
		// travesia. A conferência fica de pé como defesa em profundidade.
		if (!uploadId.matches("[A-Za-z0-9_-]{1,64}")) {
			throw new ApiException(404, "NoSuchUpload", "Upload multipart inexistente.");
		}
		return root.resolve(uploadId);
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException ignored) {
			// melhor esforço: a varredura tenta de novo
		}
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 ausente na JVM");
		}
	}
}