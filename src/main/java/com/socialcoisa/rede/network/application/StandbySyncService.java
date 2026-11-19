package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkAuthorityRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.StandbySnapshotRepository;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O banco do Control Plane nos nós de reserva: cópia diária, sincronização completa de madrugada e
 * a cópia exigida antes de um nó entrar como reserva de failover (brief cooperativo §11/§12).
 *
 * <p>O Control Plane encerra a cópia: quem lê o banco é ele, não o nó. A credencial de leitura não
 * sai daqui — o nó recebe os BYTES do dump, conferidos por digest, e nada que permita ler o banco
 * por conta própria. Isso é decisão do desenho, não detalhe: um nó de participante com usuário de
 * replicação seria um segundo caminho de leitura do dado pessoal de todo mundo.</p>
 *
 * <p>O dump sai de {@link StandbyDumpSource}. Produção usa {@code mariadb-dump}; teste usa um
 * gerador em memória, porque o que deve ser provado aqui é a POLÍTICA (quando copiar, o que é
 * bastante para assumir, o que acontece quando falha), e o formato do dump é do `mariadb-dump`.</p>
 */
@Service
public class StandbySyncService {

	private static final Logger LOG = LoggerFactory.getLogger(StandbySyncService.class);

	/** Digest do dump inteiro, com uma leitura só: o dump pode ter centenas de MB. */
	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private final StandbySnapshotRepository snapshots;
	private final NetworkAuthorityRepository authorities;
	private final NetworkNodeRepository nodes;
	private final StandbyDumpSource source;
	private final StandbyFailureRecorder failures;
	private final AuditTrail audit;
	private final Clock clock;
	private final Duration refreshInterval;
	private final long minBytes;

	public StandbySyncService(StandbySnapshotRepository snapshots, NetworkAuthorityRepository authorities,
			NetworkNodeRepository nodes, StandbyDumpSource source, StandbyFailureRecorder failures, AuditTrail audit,
			Clock clock,
			@Value("${rede.network.standby.refresh-hours:6}") long refreshHours,
			@Value("${rede.network.standby.min-bytes:64}") long minBytes) {
		this.snapshots = snapshots;
		this.authorities = authorities;
		this.nodes = nodes;
		this.source = source;
		this.failures = failures;
		this.audit = audit;
		this.clock = clock;
		this.refreshInterval = Duration.ofHours(Math.max(1, refreshHours));
		this.minBytes = Math.max(1, minBytes);
	}

	/**
	 * O que o nó de reserva precisa antes de ser promovido: pelo menos uma cópia completa e
	 * conferida. Cópia velha ainda serve (o banco de ontem é melhor que nenhum banco), mas loo
	 * painel diz que está velha — assumir com dado de três dias é decisão do operador, informada.
	 */
	@Transactional(readOnly = true)
	public StandbyStatus status(String nodeId) {
		var copies = snapshots.all().stream().filter(s -> s.nodeId().equals(nodeId)).toList();
		int ready = (int) copies.stream().filter(StandbySyncService::isReady).count();
		long failed = copies.stream().filter(s -> "FAILED".equals(s.state())).count();
		String reason = ready > 0 ? null
			: copies.stream().filter(s -> "FAILED".equals(s.state()) && s.failureReason() != null)
				.map(StandbySnapshotRepository.Snapshot::failureReason)
				.findFirst()
				.orElse("nenhuma copia do banco neste no: sincronize antes de promover");
		return new StandbyStatus(nodeId, ready, failed, copies, reason);
	}

	/**
	 * Uma cópia completa, agora. É a que a promoção dispara quando o nó está sendo marcado como
	 * reserva: promover um nó sem cópia é trocar o nome do controlador e recomeçar do zero.
	 */
	@Transactional
	public StandbySnapshotRepository.Snapshot copyNow(String actorId, String nodeId) {
		requireNode(nodeId);
		return run(actorId, nodeId, 0L);
	}

	/**
	 * Refresh automático do dia: se NENHUM nó tem cópia completa desde {@code refresh-hours}, copia
	 * para os nós que já guardam alguma. Ninguém é transformado em reserva por aqui — a lista de
	 * reservas é decisão do operador; o agendador só mantém fresca a cópia de quem já foi escolhido.
	 *
	 * @return quantos pares (nó, autoridade) foram copiados
	 */
	@Transactional
	public int refreshDaily() {
		if (snapshots.anyReadySince(clock.instant().minus(refreshInterval))) {
			return 0;
		}
		Map<String, String> pairs = new LinkedHashMap<>();
		for (var snapshot : snapshots.all()) {
			pairs.putIfAbsent(snapshot.nodeId(), snapshot.authorityId());
		}
		int copied = 0;
		for (var entry : pairs.entrySet()) {
			try {
				// O refresh faz a cópia INTEIRA no servidor: sem baixar, não há o que confirmar, e a
				// cópia ficaria em SYNCING para sempre. O veredito é o estado que sobrou na linha —
				// READY é cópia do dia; FAILED (e agora) não é; SYNCING com a cópia anterior intacta é
				// "o banco não foi liberado agora", e insistir não muda isso.
				var result = download("scheduler", entry.getKey(), 0L).state();
				if (result != null && "READY".equals(result.state())) {
					copied++;
				}
			}
			catch (ApiException unavailable) {
				// Um par que falha não impede os outros: o refresh é de manutenção, e deixar todos
				// sem cópia nova porque um nó está fora é pior que copiar para os que respondem.
				LOG.warn("Refresh do banco para {} falhou: {}", entry.getKey(), unavailable.getMessage());
			}
		}
		return copied;
	}

	/**
	 * A cópia inteira (a "sincronização total da madrugada"). Hoje é a mesma operação de
	 * {@link #copyNow}: o dump é consistente sozinho e a cópia é atômica do lado do nó (o arquivo só
	 * recebe o nome final com o digest conferido). O que a sincronização completa vai acrescentar —
	 * resto-binlog e dados de aplicação — ainda não existe, e está declarado na doc.
	 */
	@Transactional
	public StandbySnapshotRepository.Snapshot fullSync(String actorId, String nodeId) {
		return copyNow(actorId, nodeId);
	}

	/** O nó terminou (ou falhou) a gravação da cópia. Sem digest, não é cópia completa. */
	@Transactional
	public StandbySnapshotRepository.Snapshot complete(String actorId, String nodeId, String authorityId,
			Long bytes, String sha256) {
		requireNode(nodeId);
		String authority = requireAuthority(authorityId);
		var current = snapshots.find(nodeId, authority).orElseThrow(() -> new ApiException(409,
			"STANDBY_COPY_NOT_STARTED", "Não há cópia em andamento deste nó para esta autoridade."));
		if (bytes == null || sha256 == null || sha256.isBlank()) {
			return fail(actorId, nodeId, authority, current, "conclusao sem bytes e digest: copia nao conferida");
		}
		if (!sha256.matches("[0-9a-f]{64}")) {
			return fail(actorId, nodeId, authority, current, "digest fora do formato sha256 hex");
		}
		String expected = lastDigest.get(nodeId);
		if (expected != null && !expected.equals(sha256)) {
			// O nó diz ter gravado uma cópia que o servidor não mandou. Pode ser arquivo velho de
			// outra cópia, restauração de backup de disco ou adulteração — em qualquer caso, não é a
			// cópia deste banco, e uma linha READY com outro digest faria o failover assumir o banco errado.
			return fail(actorId, nodeId, authority, current,
				"o digest gravado nao e o do dump enviado: a copia nao e deste banco");
		}
		snapshots.markReady(nodeId, authority, bytes, sha256, current.term(), clock.instant());
		audit.record(actorId, "NETWORK_STANDBY_COPY_READY", nodeId,
			json("authority", authority, "term", current.term(), "bytes", bytes));
		LOG.info("Cópia do banco pronta no nó {} (autoridade {}, {} bytes)", nodeId, authority, bytes);
		return snapshots.find(nodeId, authority).orElseThrow();
	}

	/** O nó não conseguiu gravar. A cópia anterior continua registrada; o motivo fica no painel. */
	@Transactional
	public StandbySnapshotRepository.Snapshot fail(String actorId, String nodeId, String authorityId,
			String reason) {
		requireNode(nodeId);
		String authority = requireAuthority(authorityId);
		var current = snapshots.find(nodeId, authority).orElse(null);
		return fail(actorId, nodeId, authority, current, reason);
	}

	private StandbySnapshotRepository.Snapshot fail(String actorId, String nodeId, String authority,
			StandbySnapshotRepository.Snapshot current, String reason) {
		String trimmed = reason == null || reason.isBlank() ? "sem motivo declarado" : reason.trim();
		snapshots.markFailed(nodeId, authority, trimmed, clock.instant());
		audit.record(actorId, "NETWORK_STANDBY_COPY_FAILED", nodeId, json("authority", authority, "reason", trimmed));
		LOG.warn("Cópia do banco falhou no nó {} (autoridade {}): {}", nodeId, authority, trimmed);
		return snapshots.find(nodeId, authority).orElseThrow(() -> new ApiException(409,
			"STANDBY_COPY_NOT_STARTED", "Não há cópia registrada deste nó para esta autoridade."));
	}

	/**
	 * O corpo do dump. {@code offset > 0} é RETOMADA: o nó pediu a partir do byte que faltou, e a
	 * linha registra o início — não é falha do nó ter começado no meio se o servidor mandou.
	 */
	@Transactional
	public byte[] dump(String actorId, String nodeId, long offset) {
		requireNode(nodeId);
		if (offset < 0) {
			throw new ApiException(400, "STANDBY_OFFSET_INVALID", "O intervalo começa em zero ou depois; negativo não existe.");
		}
		String authority = currentAuthority();
		var record = run(actorId, nodeId, offset);
		if (!"SYNCING".equals(record.state()) && !"READY".equals(record.state())) {
			throw new ApiException(503, "STANDBY_DUMP_UNAVAILABLE", "O banco não pode ser lido agora: " + record.failureReason());
		}
		byte[] dump;
		try {
			dump = source.dump(offset);
		}
		catch (RuntimeException unavailable) {
			// Trabalho que FALHOU tem de sobreviver ao rollback, senão o painel mostraria "copiando"
			// para sempre e o operador não saberia por que não há cópia. O estado de falha é a
			// própria resposta — "não deu, e por isto" —, e ele não depende do resto da transação.
			if (org.springframework.transaction.support.TransactionSynchronizationManager
				.isSynchronizationActive()) {
				org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
					new org.springframework.transaction.support.TransactionSynchronization() {
						@Override
						public void afterCompletion(int status) {
							markFailedOutsideTransaction(nodeId, authority, unavailable.getMessage());
						}
					});
			}
			else {
				markFailedOutsideTransaction(nodeId, authority, unavailable.getMessage());
			}
			throw new ApiException(503, "STANDBY_DUMP_UNAVAILABLE", "O banco não pode ser lido agora: " + unavailable.getMessage());
		}
		// Um dump do começo é o banco inteiro: abaixo do piso, não é o banco. A RETOMADA é a cauda de
		// um dump já lido e pode ser curta por natureza — aplicar o piso a ela recusaria justamente a
		// conexão que faltava terminar.
		if (offset == 0 && dump.length < minBytes) {
			markFailedOutsideTransaction(nodeId, authority, "dump vazio ou menor que o mínimo");
			throw new ApiException(503, "STANDBY_DUMP_UNAVAILABLE", "O dump saiu pequeno demais para ser o banco.");
		}
		if (dump.length == 0) {
			throw new ApiException(416, "STANDBY_OFFSET_BEYOND_DUMP", "A retomada começou depois do fim do dump.");
		}
		return dump;
	}

	/**
	 * O dump inteiro, como o nó recebe: o trecho a partir de {@code offset} e o digest do dump
	 * COMPLETO, para ele conferir o que juntou. O digest do trecho não serviria — a retomada não tem
	 * os primeiros bytes, e conferir só a cauda aprovaria um arquivo cujo começo está corrompido.
	 */
	@Transactional
	/**
	 * Grava a falha fora da transação corrente. Usa o próprio repositório num escopo novo: um
	 * {@code update} solto no mesmo JdbcTemplate participaria do rollback e desapareceria.
	 */
	private void markFailedOutsideTransaction(String nodeId, String authority, String reason) {
		try {
			// O repositório abre a própria transação (REQUIRES_NEW): o rollback da cópia não leva
			// com ele o registro de que a cópia falhou.
			failures.record(nodeId, authority, reason, clock.instant());
		}
		catch (RuntimeException ignored) {
			LOG.warn("Não foi possível registrar a falha da cópia de {}: {}", nodeId, reason);
		}
	}

	public StandbySyncService.Copy fetch(String actorId, String nodeId, long offset) {
		return download(actorId, nodeId, offset);
	}

	/**
	 * O dump inteiro, como o nó recebe: o trecho a partir de {@code offset} e o digest do dump
	 * COMPLETO, para ele conferir o que juntou. O digest do trecho não serviria — a retomada não tem
	 * os primeiros bytes, e conferir só a cauda aprovaria um arquivo cujo começo está corrompido.
	 *
	 * <p>Baixar já pede a cópia: um nó que começa a baixar sem a linha {@code SYNCING} confirmaria
	 * depois uma cópia que o servidor nunca iniciou.</p>
	 */
	@Transactional
	public StandbySyncService.Copy download(String actorId, String nodeId, long offset) {
		// A cópia COMEÇA aqui (offset 0 = o dump inteiro no servidor): sempre do primeiro byte,
		// porque o digest é do dump inteiro. A retomada do nó grava o trecho a partir do que falta.
		byte[] start = dump(actorId, nodeId, 0L);
		byte[] body = offset <= 0 ? start : java.util.Arrays.copyOfRange(start, (int) Math.min(offset, start.length), start.length);
		if (body.length == 0 && offset > 0) {
			throw new ApiException(416, "STANDBY_OFFSET_BEYOND_DUMP", "A retomada começou depois do fim do dump.");
		}
		lastDigest.put(nodeId, sha256(start));
		return new Copy(body, sha256(start), start.length, (int) Math.min(Math.max(offset, 0), start.length),
			snapshots.find(nodeId, currentAuthority()).orElse(null));
	}

	/** Digest SHA-256 em hex minúsculo — o formato que o nó compara e a tabela guarda. */
	public static String sha256(byte[] bytes) {
		byte[] digest = java.util.Objects.requireNonNull(
			sha256OrNull(bytes, "SHA-256"), "SHA-256 ausente na JVM");
		StringBuilder hex = new StringBuilder(64);
		for (byte b : digest) {
			hex.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
		}
		return hex.toString();
	}

	private static byte[] sha256OrNull(byte[] bytes, String algorithm) {
		try {
			return java.security.MessageDigest.getInstance(algorithm).digest(bytes);
		}
		catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(algorithm + " ausente na JVM", impossible);
		}
	}

	/**
	 * O que o nó recebe numa cópia.
	 *
	 * @param body o trecho pedido (inteiro quando {@code byteStart} é 0)
	 * @param sha256 digest do dump INTEIRO
	 * @param totalBytes tamanho do dump inteiro
	 * @param byteStart primeiro byte deste trecho
	 * @param state linha da cópia depois do início (o refresh decide por ela)
	 */
	public record Copy(byte[] body, String sha256, int totalBytes, int byteStart,
			StandbySnapshotRepository.Snapshot state) {
	}

	/** Digest do último dump enviado a cada nó; a confirmação tem de bater com ele. */
	private final java.util.Map<String, String> lastDigest = new java.util.concurrent.ConcurrentHashMap<>();

	/** Só para o teste provar o caminho: a confirmação sem dump enviado não tem o que conferir. */
	void forgetLastDigest(String nodeId) {
		lastDigest.remove(nodeId);
	}

	/** A autoridade cujo banco sai por esta cópia: a vigente, ou {@code "original"} antes de qualquer promoção. */
	@Transactional(readOnly = true)
	public String currentAuthority() {
		String authorityId = authorities.current().orElse(new NetworkAuthorityRepository.AuthorityRecord(0, null, null))
			.authorityId();
		return authorityId == null ? "original" : authorityId;
	}

	private StandbySnapshotRepository.Snapshot run(String actorId, String nodeId, long offset) {
		var current = authorities.current().orElse(new NetworkAuthorityRepository.AuthorityRecord(0, null, null));
		String authority = current.authorityId() != null ? current.authorityId() : "original";
		Instant now = clock.instant();
		snapshots.startSync(nodeId, authority, current.term(), offset, actorId, now);
		audit.record(actorId, "NETWORK_STANDBY_COPY_STARTED", nodeId,
			json("authority", authority, "term", current.term(), "offset", offset));
		return snapshots.find(nodeId, authority).orElseThrow();
	}

	private static boolean isReady(StandbySnapshotRepository.Snapshot snapshot) {
		return "READY".equals(snapshot.state()) && snapshot.sha256() != null;
	}

	/** O nó tem de existir. Copiar o banco para um id inventado gravaria dado pessoal num destino que ninguém aprovou. */
	private void requireNode(String nodeId) {
		if (nodeId == null || nodeId.isBlank()) {
			throw new ApiException(400, "STANDBY_NODE_REQUIRED", "Informe o nó de reserva.");
		}
		if (nodes.findById(nodeId).isEmpty()) {
			throw new ApiException(404, "STANDBY_NODE_NOT_FOUND", "Nó de reserva inexistente: " + nodeId);
		}
	}

	private String requireAuthority(String authorityId) {
		String authority = authorityId == null || authorityId.isBlank() ? currentAuthority() : authorityId.trim();
		return authority;
	}

	private static String json(Object... pairs) {
		StringBuilder json = new StringBuilder("{");
		for (int i = 0; i < pairs.length; i += 2) {
			if (i > 0) {
				json.append(',');
			}
			json.append('"').append(pairs[i]).append("\":");
			Object value = pairs[i + 1];
			if (value instanceof Number) {
				json.append(value);
			}
			else {
				json.append('"').append(String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
			}
		}
		return json.append('}').toString();
	}

	/**
	 * O nó de reserva e o que ele pode assumir.
	 *
	 * @param readyCopies cópias completas e conferidas
	 * @param failures cópias que falharam (nunca contam para {@code readyCopies})
	 * @param reason quando nenhuma cópia serve, por quê — para o admin não adivinhar
	 */
	public record StandbyStatus(String nodeId, int readyCopies, long failures,
			List<StandbySnapshotRepository.Snapshot> copies, String reason) {

		public boolean canTakeOver() {
			return readyCopies > 0;
		}
	}
}