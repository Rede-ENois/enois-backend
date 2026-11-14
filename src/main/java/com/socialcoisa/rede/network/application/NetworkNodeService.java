package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkAuthorityRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.ProvisionTokenRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.security.Hashes;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Ciclo de vida dos nós da rede cooperativa: provisionamento, enroll, heartbeat, aprovação,
 * suspensão, quarentena e revogação.
 *
 * <p>Nenhum nó entra sozinho: o token de provisionamento é emitido por um admin, e o nó nasce em
 * PENDING_APPROVAL. Toda transição administrativa grava auditoria.</p>
 */
@Service
public class NetworkNodeService {

	/** TTL padrão do token de provisionamento (ADR-003): curto porque trafega por canal humano. */
	private static final Duration DEFAULT_TOKEN_TTL = Duration.ofMinutes(30);
	private static final int MAX_DISPLAY_NAME = 120;
	private static final int MAX_LIST = 200;

	/**
	 * Tokens vivos por conta na solicitação de auto-serviço. O token é de uso único e curto (30 min
	 * por padrão), mas um loop de cliente abriria linhas até o fim do TTL; três em aberto cobre
	 * "um servidor por perfil" com folga e ainda freia abuso.
	 */
	private static final int MAX_ALIVE_TOKENS_PER_OWNER = 3;

	/** Motivo de quarentena para nó que reportou termo maior que o da autoridade vigente. */
	static final String TERM_CONFLICT = "TERM_CONFLICT";

	private final NetworkNodeRepository nodes;
	private final NetworkAuthorityRepository authorities;
	private final ProvisionTokenRepository tokens;
	private final NodeKeyService keyService;
	private final AuditTrail audit;
	private final Clock clock;
	private final Duration tokenTtl;
	private final Duration conflictWindow;

	NetworkNodeService(NetworkNodeRepository nodes, NetworkAuthorityRepository authorities,
			ProvisionTokenRepository tokens, NodeKeyService keyService, AuditTrail audit, Clock clock,
			@Value("${rede.network.provision-token-ttl-minutes:30}") long tokenTtlMinutes,
			@Value("${rede.network.authority.conflict-quarantine-seconds:600}") long conflictSeconds) {
		this.nodes = nodes;
		this.authorities = authorities;
		this.tokens = tokens;
		this.keyService = keyService;
		this.audit = audit;
		this.clock = clock;
		this.tokenTtl = tokenTtlMinutes > 0 ? Duration.ofMinutes(tokenTtlMinutes) : DEFAULT_TOKEN_TTL;
		this.conflictWindow = Duration.ofSeconds(Math.max(30, conflictSeconds));
	}

	/**
	 * Emite o token de provisão (uso único). Ele **cria** o nó; a chave de API, que opera o nó, é
	 * emitida depois do enroll — credenciais com ciclos de vida diferentes não podem ser a mesma.
	 */
	@Transactional
	public IssuedToken issueToken(String actorId, String displayName, String ownerUserId,
			List<NodeProfile> profiles) {
		if (displayName == null || displayName.isBlank() || displayName.length() > MAX_DISPLAY_NAME) {
			throw new ApiException(400, "NODE_NAME_INVALID", "Nome do no ausente ou longo demais.");
		}
		if (ownerUserId == null || ownerUserId.isBlank()) {
			throw new ApiException(400, "NODE_OWNER_REQUIRED", "Informe a conta responsavel pelo no.");
		}
		if (profiles == null || profiles.isEmpty()) {
			throw new ApiException(400, "NODE_PROFILES_REQUIRED", "Escolha ao menos um perfil.");
		}
		String plain = NodeIds.next("npt");
		Instant now = clock.instant();
		tokens.insert(Hashes.sha256Hex(plain), displayName.trim(), ownerUserId.trim(),
			profiles.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")),
			now.plus(tokenTtl), actorId, now);
		audit.record(actorId, "NODE_PROVISION_TOKEN_ISSUED", displayName.trim(),
			"{\"owner\":\"" + ownerUserId.trim() + "\"}");
		return new IssuedToken(plain, now.plus(tokenTtl));
	}

	/**
	 * Solicitação de ingresso pelo próprio participante (interface do participante, ADR-003): o
	 * dono do token é SEMPRE a conta da sessão — o corpo do pedido não escolhe dono, senão a rota
	 * de auto-serviço emitiria credencial para conta alheia. Perfis também não: o participante
	 * declara o servidor; os perfis autorizados são negociados com o admin (que aprova o nó e
	 * emite a chave). A auditoria distingue a origem: NODE_JOIN_REQUESTED, com quem pediu.
	 */
	@Transactional
	public IssuedToken requestJoin(String ownerUserId, String displayName) {
		if (tokens.countAliveForOwner(ownerUserId, clock.instant()) >= MAX_ALIVE_TOKENS_PER_OWNER) {
			throw new ApiException(429, "NODE_JOIN_LIMIT",
				"Voce ja tem solicitacoes de ingresso em aberto: aguarde a aprovacao ou o vencimento.");
		}
		if (displayName == null || displayName.isBlank() || displayName.length() > MAX_DISPLAY_NAME) {
			throw new ApiException(400, "NODE_NAME_INVALID", "Nome do no ausente ou longo demais.");
		}
		String plain = NodeIds.next("npt");
		Instant now = clock.instant();
		tokens.insert(Hashes.sha256Hex(plain), displayName.trim(), ownerUserId, "", now.plus(tokenTtl), ownerUserId,
			now);
		audit.record(ownerUserId, "NODE_JOIN_REQUESTED", displayName.trim(), "{\"owner\":\"" + ownerUserId + "\"}");
		return new IssuedToken(plain, now.plus(tokenTtl));
	}

	/**
	 * Consome o token e cria o nó. Devolve o nó em PENDING_APPROVAL — sem chave ainda: a chave é
	 * emitida pelo fluxo administrativo e o nó só opera depois da aprovação.
	 */
	@Transactional
	public NetworkNode enroll(EnrollCommand command) {
		if (command == null || command.provisionToken() == null || command.provisionToken().isBlank()) {
			throw new ApiException(400, "NODE_TOKEN_INVALID", "Token de provisionamento ausente.");
		}
		String hash = Hashes.sha256Hex(command.provisionToken().trim());
		ProvisionTokenRepository.ProvisionTokenRecord token = tokens.findByHash(hash)
			.orElseThrow(() -> new ApiException(400, "NODE_TOKEN_INVALID", "Token de provisionamento invalido."));
		Instant now = clock.instant();
		if (token.usedAt() != null) {
			throw new ApiException(409, "NODE_TOKEN_USED", "Token de provisionamento ja utilizado.");
		}
		if (!token.expiresAt().isAfter(now)) {
			throw new ApiException(400, "NODE_TOKEN_EXPIRED", "Token de provisionamento expirado.");
		}
		// Consome ANTES de criar: duas requisições concorrentes com o mesmo token não podem gerar
		// dois nós. Quem perde a corrida recebe o 409 do uso único.
		if (!tokens.markUsed(hash, now)) {
			throw new ApiException(409, "NODE_TOKEN_USED", "Token de provisionamento ja utilizado.");
		}
		String nodeId = NodeIds.next("nd");
		nodes.create(nodeId, token.displayName(), token.ownerUserId(), List.copyOf(parseProfiles(token.profiles())),
			command.region(), command.country(), command.cpuCores(), command.memoryMb(), command.diskMb(),
			command.bandwidthMbps(), now);
		audit.record(token.ownerUserId(), "NODE_ENROLLED", nodeId, "{}");
		return nodes.findById(nodeId)
			.orElseThrow(() -> new IllegalStateException("No recem-criado nao encontrado: " + nodeId));
	}

	@Transactional(readOnly = true)
	public NetworkNode require(String nodeId) {
		return nodes.findById(nodeId)
			.orElseThrow(() -> new ApiException(404, "NODE_UNKNOWN", "No nao encontrado."));
	}

	@Transactional(readOnly = true)
	public List<NetworkNode> list(int limit) {
		return nodes.list(Math.min(Math.max(limit, 1), MAX_LIST));
	}

	@Transactional
	public NetworkNode heartbeat(NetworkNode authenticated, HeartbeatCommand beat) {
		// QUARANTINED por TERM_CONFLICT continua batendo coração: é pelo heartbeat que ele reporta
		// o termo que viu — 403 aqui fecharia o único canal de onde o conflito é detectado. Os
		// outros status não-operáveis seguem barrados como antes, inclusive quarentena manual.
		if (!termConflictQuarantined(authenticated)) {
			requireOperable(authenticated);
		}
		Instant now = clock.instant();
		nodes.touch(authenticated.id(), beat.runtimeVersion(), beat.cpuPercent(), beat.memoryMb(),
			beat.runningTasks(), now);
		NetworkNode updated = require(authenticated.id());
		quarantineOnTermConflict(updated, beat.observedTerm(), now);
		return require(authenticated.id());
	}

	/**
	 * Regra 5 da contingência (06-contingencia.md §4): nó que reporta termo MAIOR que o da
	 * autoridade vigente viu um controlador que este Control Plane não conhece — há dois mandos.
	 * O nó entra em {@code QUARANTINED} e para de receber tarefa na hora. Termo igual ou menor é
	 * normal: menor o próprio nó já recusa (term_regressao), e quem está atrasado não é culpado.
	 *
	 * <p>A quarentena de status não é liberada por relógio — o {@code findQuarantineExpired} só
	 * escolhe nós {@code ACTIVE} —, porque divergência de termo não se resolve esperando: só
	 * decisão humana (investigar qual controlador está errado) reativa o nó. A janela gravada em
	 * {@code quarantinedUntil} é só o horizonte de revisão que aparece no painel.</p>
	 */
	private static boolean termConflictQuarantined(NetworkNode node) {
		return node.status() == NodeStatus.QUARANTINED && TERM_CONFLICT.equals(node.quarantineReason());
	}

	private void quarantineOnTermConflict(NetworkNode node, Integer observedTerm, Instant now) {
		if (observedTerm == null || node.status() == NodeStatus.QUARANTINED) {
			return;
		}
		if (node.status() != NodeStatus.ACTIVE) {
			// Só nó operante pode entrar em conflito de termo: um PENDING_APPROVAL não tem tarefa a
			// perder, e trocar o status dele por QUARANTINED apagaria a fila de aprovação humana.
			return;
		}
		NetworkAuthorityRepository.AuthorityRecord current = authorities.current().orElse(null);
		if (current == null || observedTerm <= current.term()) {
			return;
		}
		Instant reviewBy = now.plus(conflictWindow);
		// changeStatus bumpa configVersion: a config assinada que o nó busca na próxima leva o
		// termo vigente, e o runtime dele para de operar com termo que ele mesmo viu ser maior.
		nodes.updateStatus(node.id(), NodeStatus.QUARANTINED, "system", now);
		nodes.quarantine(node.id(), TERM_CONFLICT, reviewBy);
		audit.record("system", "NETWORK_NODE_QUARANTINED", node.id(), "{\"reason\":\"" + TERM_CONFLICT
			+ "\",\"observedTerm\":" + observedTerm + ",\"authorityTerm\":" + current.term()
			+ ",\"reviewBy\":\"" + reviewBy + "\"}");
	}

	@Transactional
	public NetworkNode approve(String nodeId, String actorId) {
		NetworkNode node = require(nodeId);
		if (node.status() == NodeStatus.REVOKED) {
			throw new ApiException(409, "NODE_REVOKED", "No revogado nao volta: cadastre outro.");
		}
		nodes.updateStatus(nodeId, NodeStatus.ACTIVE, actorId, clock.instant());
		// Divergência de termo não expira por relógio: o único caminho de volta é decisão humana, e
		// é esta reaprovação. Sem limpar a janela, o nó ficaria ACTIVE e ainda assim fora do
		// balanceamento — liberado no papel, inútil na prática. A quarentena de saúde não é tocada
		// aqui: ela tem o próprio relógio e quem a encerra é o worker.
		if (TERM_CONFLICT.equals(node.quarantineReason())) {
			nodes.release(nodeId);
		}
		audit.record(actorId, "NODE_APPROVED", nodeId, "{}");
		return require(nodeId);
	}

	@Transactional
	public NetworkNode transitions(String nodeId, NodeStatus next, String actorId) {
		if (next != NodeStatus.SUSPENDED && next != NodeStatus.QUARANTINED && next != NodeStatus.REVOKED) {
			throw new ApiException(400, "NODE_STATUS_INVALID", "Transicao administrativa invalida.");
		}
		NetworkNode node = require(nodeId);
		if (node.status() == NodeStatus.REVOKED) {
			throw new ApiException(409, "NODE_REVOKED", "No revogado nao volta: cadastre outro.");
		}
		nodes.updateStatus(nodeId, next, actorId, clock.instant());
		if (next == NodeStatus.REVOKED) {
			// Revogar o nó revoga as chaves dele na mesma transação: chave vigente de nó revogado
			// seria acesso válido por um caminho lateral.
			keyService.revokeAllForNode(nodeId, actorId);
		}
		audit.record(actorId, "NODE_" + next.name(), nodeId, "{}");
		return require(nodeId);
	}

	/**
	 * Desconexão voluntária do participante (interface do participante, não o painel). Suspensão
	 * reversível — revogação continua sendo decisão administrativa —, com as chaves vigentes caindo
	 * na mesma transação, como na revogação: servidor desligado a próprio pedido não pode continuar
	 * autenticando até alguém do admin notar. O ator da auditoria é o próprio participante.
	 */
	@Transactional
	public NetworkNode disconnectVoluntarily(String nodeId, String ownerUserId) {
		NetworkNode node = require(nodeId);
		if (!node.ownerUserId().equals(ownerUserId)) {
			// Nó de outro participante é 404, não 403: 403 confirmaria que o id existe.
			throw new ApiException(404, "NODE_UNKNOWN", "No nao encontrado.");
		}
		if (node.status() == NodeStatus.REVOKED) {
			throw new ApiException(409, "NODE_REVOKED", "No revogado nao muda.");
		}
		if (node.status() != NodeStatus.SUSPENDED) {
			nodes.updateStatus(nodeId, NodeStatus.SUSPENDED, ownerUserId, clock.instant());
			keyService.revokeAllForNode(nodeId, ownerUserId);
			audit.record(ownerUserId, "NODE_DISCONNECTED", nodeId, "{\"voluntary\":true}");
		}
		return require(nodeId);
	}

	/**
	 * Redefine o que o participante cede. A rede respeita o limite declarado (concorrência é o
	 * teto de CPU; memória observada contra o declarado tira o nó do balanceamento), e mudar o que
	 * foi combinado é mudança de permissão: a config assinada carrega os limites, então o contrato
	 * novo só vale com versão nova — o UPDATE soma 1 em {@code configVersion}.
	 */
	@Transactional
	public NetworkNode capacity(String nodeId, Integer cpuCores, Integer memoryMb, Integer diskMb,
			Integer bandwidthMbps, String actorId) {
		if (anyNegative(cpuCores, memoryMb, diskMb, bandwidthMbps)) {
			throw new ApiException(400, "NODE_CAPACITY_INVALID", "Capacidade nao pode ser negativa.");
		}
		NetworkNode node = require(nodeId);
		requireNotRevoked(node);
		nodes.updateContract(nodeId,
			cpuCores != null ? cpuCores : node.declaredCpuCores(),
			memoryMb != null ? memoryMb : node.declaredMemoryMb(),
			diskMb != null ? diskMb : node.declaredDiskMb(),
			bandwidthMbps != null ? bandwidthMbps : node.declaredBandwidthMbps(),
			node.profiles().stream().toList(), clock.instant());
		audit.record(actorId, "NODE_CAPACITY_CHANGED", nodeId, "{\"cpuCores\":" + (cpuCores == null ? "null"
			: cpuCores) + ",\"memoryMb\":" + (memoryMb == null ? "null" : memoryMb) + "}");
		return require(nodeId);
	}

	/**
	 * Redefine os perfis autorizados. Perfis decidem quais tipos de tarefa o nó recebe e o que vai
	 * na config assinada, então a mudança também compõe versão, e o runtime aplica na próxima
	 * leitura. Uma lista vazia não é erro: é como se tira tarefa de um nó sem suspendê-lo.
	 */
	@Transactional
	public NetworkNode profiles(String nodeId, List<NodeProfile> next, String actorId) {
		NetworkNode node = require(nodeId);
		requireNotRevoked(node);
		nodes.updateContract(nodeId, node.declaredCpuCores(), node.declaredMemoryMb(), node.declaredDiskMb(),
			node.declaredBandwidthMbps(), next, clock.instant());
		audit.record(actorId, "NODE_PROFILES_CHANGED", nodeId,
			next.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",", "[", "]")));
		return require(nodeId);
	}

	private static void requireNotRevoked(NetworkNode node) {
		if (node.status() == NodeStatus.REVOKED) {
			throw new ApiException(409, "NODE_REVOKED", "No revogado nao muda: cadastre outro.");
		}
	}

	private static boolean anyNegative(Integer... values) {
		for (Integer value : values) {
			if (value != null && value < 0) {
				return true;
			}
		}
		return false;
	}

	private static void requireOperable(NetworkNode node) {
		if (node.status() == NodeStatus.REVOKED) {
			throw new ApiException(403, "NODE_REVOKED", "No revogado.");
		}
		if (node.status() == NodeStatus.QUARANTINED) {
			throw new ApiException(403, "NODE_QUARANTINED", "No em quarentena.");
		}
		if (node.status() == NodeStatus.SUSPENDED) {
			throw new ApiException(403, "NODE_SUSPENDED", "No suspenso.");
		}
		if (node.status() == NodeStatus.PENDING_APPROVAL) {
			throw new ApiException(403, "NODE_PENDING_APPROVAL", "No aguardando aprovacao.");
		}
	}

	static Set<NodeProfile> parseProfiles(String csv) {
		if (csv == null || csv.isBlank()) {
			return Set.of();
		}
		try {
			return java.util.Arrays.stream(csv.split(","))
				.map(String::trim)
				.filter(item -> !item.isEmpty())
				.map(NodeProfile::valueOf)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		}
		catch (IllegalArgumentException unknown) {
			throw new ApiException(400, "NODE_PROFILES_INVALID", "Perfil de no desconhecido.");
		}
	}

	public record IssuedToken(String token, Instant expiresAt) {
	}

	public record EnrollCommand(String provisionToken, String region, String country, int cpuCores, int memoryMb,
			int diskMb, int bandwidthMbps) {
	}

	public record HeartbeatCommand(String runtimeVersion, Double cpuPercent, Integer memoryMb,
			Integer runningTasks, Integer observedTerm) {
	}
}
