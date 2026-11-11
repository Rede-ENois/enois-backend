package com.socialcoisa.rede.network.application;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.media.application.MediaTemporaryFiles;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.network.application.port.out.MediaJobRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkTaskRepository;
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.MediaJobStatus;
import com.socialcoisa.rede.network.domain.MediaTranscodeJob;
import com.socialcoisa.rede.network.domain.MediaTranscodeSpec;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.TaskStatus;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Transcode de mídia executado num nó {@code NODE_MEDIA} (fase 3).
 *
 * <p>O critério de aceite é "transcode executa no nó; resultado verificado por hash e tamanho". As
 * duas metades são deliberadamente assimétricas:</p>
 *
 * <ul>
 * <li><b>O nó executa e declara.</b> Ele recebe o objeto da tarefa em
 * {@code GET /network/media/{id}/source} (só enquanto a tarefa estiver designada a ele), roda a
 * receita fixa de {@link MediaTranscodeSpec} e envia o arquivo em
 * {@code PUT /network/media/{id}/result}.</li>
 * <li><b>Quem decide é o Control Plane.</b> Ao receber o arquivo, ele mede tamanho e sha-256
 * <em>enquanto</em> grava no staging e persiste os valores medidos. No
 * {@code POST /network/tasks/{id}/result} o nó declara o que produziu; a declaração só é aceita se
 * for idêntica ao que o arquivo entregue contém. Um nó que inventa um hash bonito não conclui a
 * tarefa.</li>
 * </ul>
 *
 * <p>O artefato fica em {@code _network-staging/}, fora do proxy público: verificá-lo não é
 * publicá-lo. Trocar o {@code cdn_url} do asset para o objeto transcodificado muda o que o leitor
 * recebe — é decisão de produto e não entra aqui.</p>
 */
@Service
public class MediaTranscodeService implements MediaResultVerifier {

	private static final Logger LOG = LoggerFactory.getLogger(MediaTranscodeService.class);

	/** Prefácio das chaves que o proxy público nunca serve: artefato de nó ainda não consumido. */
	public static final String STAGING_PREFIX = "_network-staging/";

	/** Teto da origem e do resultado: o mesmo teto de transcode do pod. */
	public static final long MAX_BYTES = 150L * 1024L * 1024L;

	private static final int COPY_BUFFER = 64 * 1024;
	private static final int MAX_LIST = 200;

	private final MediaJobRepository jobs;
	private final MediaAssetRepository assets;
	/* Vazio quando o storage e o ImageKit: nao ha objeto proprio para servir nem gravar. */
	private final org.springframework.beans.factory.ObjectProvider<MediaObjectStore> storeProvider;
	private final MediaTemporaryFiles temporaryFiles;
	private final NetworkTaskService tasks;
	private final NetworkTaskRepository taskRepository;
	private final ObjectMapper objectMapper;
	private final AuditTrail audit;
	private final Clock clock;

	MediaTranscodeService(MediaJobRepository jobs, MediaAssetRepository assets, org.springframework.beans.factory.ObjectProvider<MediaObjectStore> storeProvider,
			MediaTemporaryFiles temporaryFiles, NetworkTaskService tasks, NetworkTaskRepository taskRepository, ObjectMapper objectMapper,
			AuditTrail audit, Clock clock) {
		this.jobs = jobs;
		this.assets = assets;
		this.storeProvider = storeProvider;
		this.temporaryFiles = temporaryFiles;
		this.tasks = tasks;
		this.taskRepository = taskRepository;
		this.objectMapper = objectMapper;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * Pede o transcode de uma mídia já armazenada. A origem é lida e fixada aqui (tamanho e hash):
	 * sem isso, "o resultado é o transcode desta origem" não seria verificável depois.
	 *
	 * <p>Idempotente pela origem: a chave de idempotência da tarefa inclui o hash do arquivo, então
	 * pedir de novo o mesmo objeto devolve o mesmo trabalho em vez de enfileirar outro.</p>
	 */
	@Transactional
	public MediaTranscodeJob request(String mediaId) {
		if (mediaId == null || mediaId.isBlank()) {
			throw new ApiException(400, "MEDIA_ID_REQUIRED", "Informe a midia.");
		}
		MediaAsset asset = assets.findById(mediaId.trim())
			.orElseThrow(() -> new ApiException(404, "MEDIA_NOT_FOUND", "Midia nao encontrada."));
		String mime = asset.mimeType() == null ? "" : asset.mimeType().trim().toLowerCase(Locale.ROOT);
		if (!MediaTranscodeSpec.VIDEO_TYPES.contains(mime)) {
			throw new ApiException(400, "MEDIA_NOT_TRANSCODABLE", "So video passa por transcode.");
		}
		Optional<MediaTranscodeJob> existing = jobs.findByMediaId(asset.id());
		if (existing.isPresent()) {
			return existing.get();
		}
		SourceDigest source = digestSource(asset.objectKey());
		String resultKey = MediaTranscodeSpec.resultKeyFor(asset.id(), mime);
		Instant now = clock.instant();
		MediaTranscodeJob job = new MediaTranscodeJob(asset.id(), asset.objectKey(), mime, source.bytes(),
			source.sha256(), resultKey, null, null, MediaJobStatus.PENDING, null, null, now, null);
		NetworkTask task = enqueue(job, now);
		jobs.create(new MediaTranscodeJob(job.mediaId(), job.sourceKey(), job.mimeType(), job.sourceBytes(),
			job.sourceSha256(), job.resultKey(), null, null, MediaJobStatus.PENDING, task.id(), null, now, null));
		audit.record("system", "NETWORK_MEDIA_TRANSCODE_REQUESTED", asset.id(),
			"{\"taskId\":\"" + task.id() + "\",\"sourceBytes\":" + source.bytes() + "}");
		return jobs.findByMediaId(asset.id()).orElse(job);
	}

	private NetworkTask enqueue(MediaTranscodeJob job, Instant now) {
		MediaTranscodeSpec spec = job.spec(MAX_BYTES);
		String payload;
		try {
			payload = objectMapper.writeValueAsString(Map.of("mediaId", spec.mediaId(), "sourceKey",
				spec.sourceKey(), "mimeType", spec.mimeType(), "sourceBytes", spec.sourceBytes(), "sourceSha256",
				spec.sourceSha256(), "resultKey", spec.resultKey(), "resultBudgetBytes", spec.resultBudgetBytes()));
		}
		catch (RuntimeException serialization) {
			throw new IllegalStateException("Falha ao serializar payload de transcode", serialization);
		}
		// A chave de idempotência é o hash de (mídia, hash da origem): origem nova é trabalho novo, e
		// a mesma origem pedida de novo continua sendo a mesma tarefa. Vai hasheada porque a coluna
		// tem 80 caracteres e o par id+sha256 não cabe — e o par cru não acrescentaria nada, já que
		// os dois valores estão na linha do job.
		String idempotency = "media:" + com.socialcoisa.rede.shared.security.Hashes
			.sha256Hex(job.mediaId() + ":" + job.sourceSha256());
		return tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.MEDIA_TRANSCODE, idempotency, payload,
			DataClass.PUBLIC_MEDIA, now.plus(2, ChronoUnit.HOURS)));
	}

	/**
	 * O objeto da tarefa, para o nó que tem o transcode designado a ele. É a única forma de o nó
	 * receber o arquivo: sem a tarefa designada, o nó não lê mídia nenhuma.
	 */
	@Transactional(readOnly = true)
	public MediaObjectStore.StoredObject openSource(NetworkNode node, String mediaId, String range) {
		MediaTranscodeJob job = requireAssigned(node, mediaId);
		try {
			return store().read(job.sourceKey(), range);
		}
		catch (RuntimeException missing) {
			throw new ApiException(404, "MEDIA_NOT_FOUND", "Midia nao encontrada.");
		}
	}

	/**
	 * Recebe o resultado do nó: grava no staging e MEDE tamanho e sha-256 enquanto copia.
	 *
	 * <p>A medição é do arquivo, não da declaração. O limite é cortado durante a cópia — um corpo
	 * maior que o orçamento é recusado sem que o bucket cresça sem limite.</p>
	 */
	@Transactional
	public StoredResult storeResult(NetworkNode node, String mediaId, String contentType, InputStream body) {
		MediaTranscodeJob job = requireAssigned(node, mediaId);
		MessageDigest digest = sha256();
		long measured;
		// O storage exige o tamanho antes de gravar, e o tamanho que o nó informa não vale como
		// medida. Então o corpo é copiado para um temporário enquanto é contado e hasheado; só
		// depois, com o tamanho medido, ele vai para o bucket. O temporário é apagado sempre.
		java.nio.file.Path spool;
		try {
			spool = temporaryFiles.create("network-media-", ".result");
		}
		catch (IOException unavailable) {
			LOG.error("network_media_temp_unavailable mediaId={} cause={}", job.mediaId(),
				unavailable.getClass().getName());
			throw new ApiException(503, "MEDIA_UPLOAD_UNAVAILABLE",
				"Nao foi possivel processar o arquivo agora. Tente novamente em instantes.", unavailable);
		}
		try {
			CountingInputStream counted = new CountingInputStream(new DigestInputStream(body, digest), MAX_BYTES);
			try (java.io.OutputStream sink = java.nio.file.Files.newOutputStream(spool)) {
				counted.transferTo(sink);
			}
			measured = counted.count();
			if (measured == 0) {
				throw new ApiException(422, "MEDIA_EMPTY", "Arquivo vazio nao e resultado de transcode.");
			}
			try (InputStream stored = java.nio.file.Files.newInputStream(spool)) {
				store().write(job.resultKey(), contentType, measured, stored);
			}
		}
		catch (CountingInputStream.OverBudget tooBig) {
			LOG.warn("network_media_over_budget mediaId={} node={}", job.mediaId(), node.id());
			throw new ApiException(413, "MEDIA_TOO_LARGE", "Resultado maior que o limite do transcode.", tooBig);
		}
		catch (IOException unreadable) {
			throw new ApiException(400, "MEDIA_UPLOAD_FAILED", "Nao foi possivel ler o arquivo enviado.", unreadable);
		}
		catch (ApiException refused) {
			throw refused;
		}
		catch (RuntimeException failed) {
			LOG.error("network_media_write_failed mediaId={} cause={}", job.mediaId(), failed.getClass().getName());
			throw new ApiException(502, "MEDIA_STORAGE_WRITE_FAILED",
				"Nao foi possivel gravar a midia agora.", failed);
		}
		finally {
			try {
				java.nio.file.Files.deleteIfExists(spool);
			}
			catch (IOException ignored) {
				LOG.warn("network_media_temp_cleanup_failed mediaId={}", job.mediaId());
			}
		}
		String sha = HexFormat.of().formatHex(digest.digest());
		jobs.markMeasured(job.mediaId(), measured, sha);
		audit.record(node.id(), "NETWORK_MEDIA_RESULT_STORED", job.mediaId(),
			"{\"bytes\":" + measured + ",\"sha256\":\"" + sha + "\"}");
		return new StoredResult(job.mediaId(), job.resultKey(), measured, sha);
	}

	/** O nó declarou sucesso: confere a declaração contra o arquivo que ele mesmo entregou. */
	@Override
	@Transactional
	public Verification verify(NetworkTask task, String output) {
		Optional<MediaTranscodeJob> found = jobs.findByTaskId(task.id());
		if (found.isEmpty()) {
			return Verification.refused("MEDIA_JOB_UNKNOWN", "Tarefa de midia sem job correspondente.", false);
		}
		MediaTranscodeJob job = found.get();
		if (job.verified()) {
			return Verification.accepted();
		}
		Declared declared = parseDeclared(output);
		if (declared == null) {
			return Verification.refused("MEDIA_RESULT_INVALID", "Resultado sem chave, tamanho e hash.", true);
		}
		if (!job.resultKey().equals(declared.resultKey())) {
			return Verification.refused("MEDIA_RESULT_INVALID", "Chave do resultado nao e a combinada.", false);
		}
		if (job.resultBytes() == null || job.resultSha256() == null) {
			// O nó declarou antes de entregar o arquivo: não há o que conferir. Retentável porque o
			// envio do arquivo e a declaração são dois passos, e a ordem dos dois não é garantida.
			return Verification.refused("MEDIA_RESULT_MISSING", "Nenhum arquivo entregue para conferencia.", true);
		}
		if (declared.bytes() != job.resultBytes() || !declared.sha256().equalsIgnoreCase(job.resultSha256())) {
			jobs.markRejected(job.mediaId(), "MEDIA_RESULT_MISMATCH: declarado " + declared.bytes() + "/"
				+ shortHash(declared.sha256()) + ", medido " + job.resultBytes() + "/"
				+ shortHash(job.resultSha256()), clock.instant());
			audit.record(task.assignedNodeId(), "NETWORK_MEDIA_RESULT_REJECTED", job.mediaId(),
				"{\"reason\":\"declared_mismatch\"}");
			return Verification.refused("MEDIA_RESULT_MISMATCH",
				"O resultado declarado nao corresponde ao arquivo entregue.", false);
		}
		if (job.resultSha256().equalsIgnoreCase(job.sourceSha256())) {
			// Arquivo idêntico à origem: nada foi transcodificado.
			jobs.markRejected(job.mediaId(), "MEDIA_NOT_TRANSCODED: resultado identico a origem", clock.instant());
			return Verification.refused("MEDIA_NOT_TRANSCODED", "O resultado e igual a origem.", false);
		}
		jobs.markVerified(job.mediaId(), job.resultBytes(), job.resultSha256(), clock.instant());
		audit.record(task.assignedNodeId(), "NETWORK_MEDIA_VERIFIED", job.mediaId(),
			"{\"bytes\":" + job.resultBytes() + ",\"sha256\":\"" + job.resultSha256() + "\"}");
		return Verification.accepted();
	}

	/** O nó declarou falha: o motivo fica na linha para o painel, sem mudar o estado. */
	@Override
	@Transactional
	public void recordFailure(NetworkTask task, String error, boolean retryable) {
		jobs.findByTaskId(task.id()).ifPresent(job -> jobs.recordFailure(job.mediaId(), error));
	}

	@Transactional(readOnly = true)
	public List<MediaTranscodeJob> list(int limit) {
		return jobs.listRecent(Math.min(Math.max(limit, 1), MAX_LIST));
	}

	@Transactional(readOnly = true)
	public long countByStatus(MediaJobStatus status) {
		return jobs.countByStatus(status);
	}

	/** O storage de objetos do pod; ausente quando a midia e servida pelo ImageKit. */
	private MediaObjectStore store() {
		MediaObjectStore store = storeProvider.getIfAvailable();
		if (store == null) {
			throw new ApiException(503, "MEDIA_STORAGE_DISABLED", "Armazenamento de midia indisponivel.");
		}
		return store;
	}

	// ---------------------------------------------------------------- credencial e designação

	/**
	 * A permissão é do perfil e da designação, nesta ordem: primeiro o perfil {@code NODE_MEDIA}
	 * (capacidade não dá permissão), depois a tarefa precisa estar designada a este nó e vigente.
	 * Sem a segunda checagem, um nó com o perfil certo leria e escreveria qualquer mídia.
	 */
	private MediaTranscodeJob requireAssigned(NetworkNode node, String mediaId) {
		if (!node.status().canOperate()) {
			throw new ApiException(403, "NODE_INACTIVE", "No nao esta ativo.");
		}
		if (!node.profiles().contains(NodeProfile.NODE_MEDIA) && !node.profiles().contains(NodeProfile.NODE_LOCAL)) {
			throw new ApiException(403, "NODE_PROFILE_REQUIRED", "No sem perfil de midia.");
		}
		if (mediaId == null || mediaId.isBlank()) {
			throw new ApiException(400, "MEDIA_ID_REQUIRED", "Informe a midia.");
		}
		MediaTranscodeJob job = jobs.findByMediaId(mediaId.trim())
			.orElseThrow(() -> new ApiException(404, "MEDIA_JOB_UNKNOWN", "Transcode nao solicitado para esta midia."));
		if (job.taskId() == null) {
			throw new ApiException(409, "MEDIA_JOB_UNASSIGNED", "Transcode sem tarefa ativa.");
		}
		NetworkTask task = taskRepository.findById(job.taskId())
			.orElseThrow(() -> new ApiException(409, "MEDIA_JOB_UNASSIGNED", "Transcode sem tarefa ativa."));
		Instant now = clock.instant();
		boolean vigente = task.taskType() == TaskType.MEDIA_TRANSCODE && task.status() == TaskStatus.DISPATCHED
			&& task.assignedTo(node.id()) && !task.claimExpired(now);
		if (!vigente) {
			throw new ApiException(403, "MEDIA_NOT_ASSIGNED", "Este transcode nao esta designado a este no.");
		}
		return job;
	}

	// ---------------------------------------------------------------- leitura da origem

	/** Lê a origem inteira e devolve tamanho e sha-256 medidos. */
	private SourceDigest digestSource(String objectKey) {
		MediaObjectStore.StoredObject object;
		try {
			object = store().read(objectKey, null);
		}
		catch (RuntimeException missing) {
			throw new ApiException(503, "MEDIA_STORAGE_UNAVAILABLE", "Armazenamento de midia indisponivel.");
		}
		MessageDigest digest = sha256();
		long total = 0;
		byte[] buffer = new byte[COPY_BUFFER];
		try (InputStream body = object.body()) {
			int read;
			while ((read = body.read(buffer)) > 0) {
				total += read;
				if (total > MAX_BYTES) {
					throw new ApiException(413, "MEDIA_TOO_LARGE", "Arquivo maior que o limite do transcode.");
				}
				digest.update(buffer, 0, read);
			}
		}
		catch (IOException unreadable) {
			throw new ApiException(503, "MEDIA_STORAGE_UNAVAILABLE", "Armazenamento de midia indisponivel.");
		}
		if (total == 0) {
			throw new ApiException(422, "MEDIA_EMPTY", "Arquivo de midia vazio.");
		}
		return new SourceDigest(total, HexFormat.of().formatHex(digest.digest()));
	}

	// ---------------------------------------------------------------- apoio

	private Declared parseDeclared(String output) {
		if (output == null || output.isBlank()) {
			return null;
		}
		try {
			JsonNode root = objectMapper.readTree(output);
			if (root == null || !root.isObject()) {
				return null;
			}
			String key = textOf(root, "resultKey");
			String sha = textOf(root, "resultSha256");
			JsonNode bytes = root.get("resultBytes");
			if (key == null || sha == null || bytes == null || !bytes.canConvertToLong()) {
				return null;
			}
			return new Declared(key, bytes.asLong(), sha);
		}
		catch (RuntimeException malformed) {
			return null;
		}
	}

	private static String textOf(JsonNode root, String field) {
		JsonNode value = root.get(field);
		return value == null || !value.isString() || value.asString().isBlank() ? null : value.asString();
	}

	private static String shortHash(String hash) {
		return hash == null ? "null" : hash.substring(0, Math.min(12, hash.length()));
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 indisponivel", impossible);
		}
	}

	private record SourceDigest(long bytes, String sha256) {
	}

	private record Declared(String resultKey, long bytes, String sha256) {
	}

	/** Resultado medido na entrega do nó. */
	public record StoredResult(String mediaId, String resultKey, long bytes, String sha256) {
	}

	/** Corte do corpo no teto do transcode, com o tamanho medido disponível ao final da cópia. */
	static final class CountingInputStream extends FilterInputStream {

		private final long limit;
		private long count;

		CountingInputStream(InputStream in, long limit) {
			super(in);
			this.limit = limit;
		}

		@Override
		public int read() throws IOException {
			int value = super.read();
			if (value >= 0 && ++count > limit) {
				throw new OverBudget();
			}
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int read = super.read(buffer, offset, length);
			if (read > 0) {
				count += read;
				if (count > limit) {
					throw new OverBudget();
				}
			}
			return read;
		}

		long count() {
			return count;
		}

		static final class OverBudget extends IOException {
			OverBudget() {
				super("corpo acima do limite do transcode");
			}
		}
	}
}