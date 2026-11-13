package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.NetworkDashboardService;
import com.socialcoisa.rede.network.application.NetworkNodeService;
import com.socialcoisa.rede.network.application.NodeKeyService;
import com.socialcoisa.rede.network.application.port.out.NodeKeyRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Administração da rede cooperativa. Tudo aqui é exclusivo do admin: gera o token de
 * provisionamento, emite e revoga a API key de cada nó, aprova, suspende, coloca em quarentena e
 * revoga.
 *
 * <p>Estas rotas ficam sob {@code /admin}, portanto já passam por {@code AdminRouteInterceptor}
 * (trava central) e ainda chamam {@code AdminGuard} aqui, como defesa em profundidade. A API key só
 * aparece em claro na resposta do POST que a gera — as listagens mostram apenas o prefixo.</p>
 */
@RestController
@RequestMapping("/admin/network/nodes")
class AdminNetworkController {

	private final NetworkNodeService nodes;
	private final NodeKeyService keys;
	private final NetworkDashboardService dashboard;
	private final AuditTrail audit;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	AdminNetworkController(NetworkNodeService nodes, NodeKeyService keys, NetworkDashboardService dashboard,
			AuditTrail audit, CurrentUserResolver currentUserResolver, AdminGuard adminGuard) {
		this.nodes = nodes;
		this.keys = keys;
		this.dashboard = dashboard;
		this.audit = audit;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	/**
	 * O nó como o painel o vê. `keyId`, `keyPrefix` e `keyUsed` vêm da chave vigente: sem eles o
	 * admin não sabe se o nó já tem chave, se ela já foi usada, nem qual revogar. A chave em claro
	 * nunca aparece aqui. A capacidade declarada (`declared*`) também vai: é o valor vigente que a
	 * tela de capacidade edita — sem ele o admin muda de capacidade sem ver o que o participante
	 * cedeu hoje.
	 */
	record NodeView(String id, String displayName, String ownerUserId, String status, List<String> profiles,
			String region, String runtimeVersion, Instant lastSeenAt, boolean operable, String keyId,
			String keyPrefix, boolean keyUsed, Integer latencyMs, int failureStreak, boolean degraded,
			String quarantineReason, Instant quarantinedUntil, int declaredCpuCores, int declaredMemoryMb,
			int declaredDiskMb, int declaredBandwidthMbps) {

		/**
		 * O nó como o painel o vê. {@code degraded} e {@code quarantinedUntil} vêm da saúde medida, que
		 * não é {@code status}: um nó ACTIVE pode estar fora do balanceamento por falha, e sem esses
		 * campos o admin veria "ativo" sem entender por que a fila não anda.
		 *
		 * <p>Motivo de quarentena de STATUS com janela vencida segue visível: a de termo divergente
		 * não sai sozinha, e esconder o motivo depois do prazo de revisão deixaria o painel mostrando
		 * um nó parado sem dizer por quê.</p>
		 */
		static NodeView of(NetworkNode node, NodeKeyRepository.NodeKeyRecord key, Instant now) {
			boolean quarantined = node.quarantined(now);
			boolean showQuarantine = quarantined
				|| (node.status() == NodeStatus.QUARANTINED && node.quarantineReason() != null);
			return new NodeView(node.id(), node.displayName(), node.ownerUserId(), node.status().name(),
				node.profiles().stream().map(Enum::name).sorted().toList(), node.region(), node.runtimeVersion(),
				node.lastSeenAt(), node.canOperate(), key == null ? null : key.id(),
				key == null ? null : key.keyPrefix(), key != null && key.lastUsedAt() != null,
				node.observedLatencyMs(), node.consecutiveFailures(), showQuarantine || node.slow() || node.failing(),
				showQuarantine ? node.quarantineReason() : null, showQuarantine ? node.quarantinedUntil() : null,
				node.declaredCpuCores(), node.declaredMemoryMb(), node.declaredDiskMb(),
				node.declaredBandwidthMbps());
		}
	}

	record IssueTokenRequest(String displayName, String ownerUserId, List<String> profiles) {
	}

	record IssuedTokenView(String token, Instant expiresAt) {
	}

	record IssuedKeyView(String keyId, String apiKey, String prefix) {
	}

	record StatusRequest(String status) {
	}

	/** Campos ausentes mantêm o valor vigente: o painel envia só o que o admin mudou. */
	record CapacityRequest(Integer cpuCores, Integer memoryMb, Integer diskMb, Integer bandwidthMbps) {
	}

	record ProfilesRequest(List<String> profiles) {
	}

	@GetMapping
	List<NodeView> list(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "200") int limit) {
		admin(sessionToken);
		List<NetworkNode> found = nodes.list(limit);
		// Uma consulta para as chaves de todos os nós: a tela lista até 200 e abriria 200 consultas.
		Map<String, NodeKeyRepository.NodeKeyRecord> activeKeys = keys.activeKeysFor(
			found.stream().map(NetworkNode::id).toList());
		Instant now = Instant.now();
		return found.stream().map(node -> NodeView.of(node, activeKeys.get(node.id()), now)).toList();
	}

	/**
	 * Agregados do topo do painel: a rede em números, calculados sobre a mesma lista que {@code GET
	 * /} devolve linha a linha. A tela não precisa mais contar de cabeça quantos nós estão ativos,
	 * degradados ou em quarentena, nem somar capacidade e médias de CPU/memória.
	 */
	@GetMapping("/dashboard")
	NetworkDashboardService.Dashboard dashboard(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		admin(sessionToken);
		return dashboard.dashboard();
	}

	@GetMapping("/{id}")
	NodeView byId(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		admin(sessionToken);
		return view(nodes.require(id));
	}

	/** Token de uso único que cria o nó; não é a chave de operação (ADR-003). */
	@PostMapping("/provision-token")
	IssuedTokenView issueToken(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) IssueTokenRequest request) {
		CurrentUser admin = admin(sessionToken);
		var profiles = request == null ? List.<String>of() : request.profiles();
		var issued = nodes.issueToken(admin.id().value(), request == null ? null : request.displayName(),
			request == null ? null : request.ownerUserId(), parseProfiles(profiles));
		return new IssuedTokenView(issued.token(), issued.expiresAt());
	}

	/** Gera a API key DESTE nó. A chave em claro aparece só nesta resposta. */
	@PostMapping("/{id}/keys")
	IssuedKeyView issueKey(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		CurrentUser admin = admin(sessionToken);
		var issued = keys.issue(id, admin.id().value());
		return new IssuedKeyView(issued.keyId(), issued.plainTextKey(), issued.prefix());
	}

	@PostMapping("/{id}/keys/{keyId}/revoke")
	NodeView revokeKey(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id, @PathVariable String keyId) {
		CurrentUser admin = admin(sessionToken);
		keys.revoke(id, keyId, admin.id().value());
		return view(nodes.require(id));
	}

	@PostMapping("/{id}/approve")
	NodeView approve(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		CurrentUser admin = admin(sessionToken);
		return view(nodes.approve(id, admin.id().value()));
	}

	@PostMapping("/{id}/status")
	NodeView status(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id, @RequestBody(required = false) StatusRequest request) {
		CurrentUser admin = admin(sessionToken);
		if (request == null || request.status() == null) {
			throw new ApiException(400, "NODE_STATUS_REQUIRED", "Informe o status.");
		}
		NodeStatus next;
		try {
			next = NodeStatus.valueOf(request.status().trim().toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException unknown) {
			throw new ApiException(400, "NODE_STATUS_INVALID", "Status desconhecido.");
		}
		return view(nodes.transitions(id, next, admin.id().value()));
	}

	/** Redefine o que o participante cede (campo ausente = mantém). Mudança de permissão: compõe versão. */
	@PostMapping("/{id}/capacity")
	NodeView capacity(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id, @RequestBody(required = false) CapacityRequest request) {
		CurrentUser admin = admin(sessionToken);
		if (request == null) {
			throw new ApiException(400, "NODE_CAPACITY_REQUIRED", "Informe a capacidade.");
		}
		return view(nodes.capacity(id, request.cpuCores(), request.memoryMb(), request.diskMb(),
			request.bandwidthMbps(), admin.id().value()));
	}

	/** Redefine os perfis autorizados (lista vazia = nó sem tarefa, sem precisar suspendê-lo). */
	@PostMapping("/{id}/profiles")
	NodeView profiles(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id, @RequestBody(required = false) ProfilesRequest request) {
		CurrentUser admin = admin(sessionToken);
		List<NodeProfile> next = parseProfilesOptional(request == null ? null : request.profiles());
		return view(nodes.profiles(id, next, admin.id().value()));
	}

	/**
	 * O histórico administrativo do nó: a trilha de auditoria que cada ação desta tela já grava,
	 * lida de volta mais nova primeiro. A página por nó mostra "histórico" sem uma tabela própria —
	 * a trilha que prova as ações é a mesma que as conta.
	 */
	@GetMapping("/{id}/history")
	List<AuditRecordView> history(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id, @RequestParam(defaultValue = "50") int limit) {
		admin(sessionToken);
		nodes.require(id); // Nó desconhecido é 404, não histórico vazio.
		int capped = Math.min(Math.max(limit, 1), 200);
		return audit.recentForTarget(id, capped).stream()
			.map(record -> new AuditRecordView(record.id(), record.actorId(), record.action(), record.metadata(),
				record.createdAt()))
			.toList();
	}

	record AuditRecordView(String id, String actorId, String action, String metadata, Instant createdAt) {
	}

	/** Um nó com a chave vigente dele: a tela precisa dos dois para desenhar a linha. */
	private NodeView view(NetworkNode node) {
		return NodeView.of(node, keys.activeKeyFor(node.id()), Instant.now());
	}

	private CurrentUser admin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}

	private static List<NodeProfile> parseProfiles(List<String> raw) {
		if (raw == null || raw.isEmpty()) {
			throw new ApiException(400, "NODE_PROFILES_REQUIRED", "Escolha ao menos um perfil.");
		}
		return parseProfilesOptional(raw);
	}

	/** Aqui lista vazia é legítima (tirar tarefa do nó); só nomes desconhecidos recusam. */
	private static List<NodeProfile> parseProfilesOptional(List<String> raw) {
		if (raw == null) {
			return List.of();
		}
		try {
			return raw.stream()
				.filter(item -> item != null && !item.isBlank())
				.map(item -> NodeProfile.valueOf(item.trim().toUpperCase(java.util.Locale.ROOT)))
				.distinct()
				.toList();
		}
		catch (IllegalArgumentException unknown) {
			throw new ApiException(400, "NODE_PROFILES_INVALID", "Perfil de no desconhecido.");
		}
	}
}
