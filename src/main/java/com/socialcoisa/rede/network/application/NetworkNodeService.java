package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

	private final NetworkNodeRepository nodes;
	private final ProvisionTokenRepository tokens;
	private final NodeKeyService keyService;
	private final AuditTrail audit;
	private final Clock clock;
	private final Duration tokenTtl;

	NetworkNodeService(NetworkNodeRepository nodes, ProvisionTokenRepository tokens, NodeKeyService keyService,
			AuditTrail audit, Clock clock,
			@Value("${rede.network.provision-token-ttl-minutes:30}") long tokenTtlMinutes) {
		this.nodes = nodes;
		this.tokens = tokens;
		this.keyService = keyService;
		this.audit = audit;
		this.clock = clock;
		this.tokenTtl = tokenTtlMinutes > 0 ? Duration.ofMinutes(tokenTtlMinutes) : DEFAULT_TOKEN_TTL;
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
		requireOperable(authenticated);
		nodes.touch(authenticated.id(), beat.runtimeVersion(), beat.cpuPercent(), beat.memoryMb(),
			beat.runningTasks(), clock.instant());
		return require(authenticated.id());
	}

	@Transactional
	public NetworkNode approve(String nodeId, String actorId) {
		NetworkNode node = require(nodeId);
		if (node.status() == NodeStatus.REVOKED) {
			throw new ApiException(409, "NODE_REVOKED", "No revogado nao volta: cadastre outro.");
		}
		nodes.updateStatus(nodeId, NodeStatus.ACTIVE, actorId, clock.instant());
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
			Integer runningTasks) {
	}
}
