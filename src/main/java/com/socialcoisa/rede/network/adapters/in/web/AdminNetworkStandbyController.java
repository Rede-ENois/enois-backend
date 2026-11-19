package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.NetworkAuthorityService;
import com.socialcoisa.rede.network.application.StandbySyncService;
import com.socialcoisa.rede.network.application.port.out.NetworkAuthorityRepository.AuthorityRecord;
import com.socialcoisa.rede.network.application.port.out.StandbySnapshotRepository;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * O banco do Control Plane nos nós de reserva (V360, brief cooperativo §11/§12): a cópia, o estado
 * dela e a promoção que exige a cópia.
 *
 * <p>Duas credenciais, de propósito: o <b>admin</b> pede a cópia, lê o estado e promove; o <b>nó</b>
 * baixa o dump e confirma o digest com a própria API key — ele só confirma a cópia do próprio id.</p>
 */
@RestController
@RequestMapping("/admin/network")
class AdminNetworkStandbyController {

	private final StandbySyncService standby;
	private final NetworkAuthorityService authorities;
	private final AuditTrail audit;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	AdminNetworkStandbyController(StandbySyncService standby, NetworkAuthorityService authorities, AuditTrail audit,
			CurrentUserResolver currentUserResolver, AdminGuard adminGuard) {
		this.standby = standby;
		this.authorities = authorities;
		this.audit = audit;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	record CopyView(String nodeId, String authorityId, String state, int term, Instant startedAt,
			Instant completedAt, Long bytes, long byteStart, String sha256, String failureReason,
			String requestedBy) {

		static CopyView of(StandbySnapshotRepository.Snapshot snapshot) {
			return new CopyView(snapshot.nodeId(), snapshot.authorityId(), snapshot.state(), snapshot.term(),
				snapshot.startedAt(), snapshot.completedAt(), snapshot.bytes(), snapshot.byteStart(),
				snapshot.sha256(), snapshot.failureReason(), snapshot.requestedBy());
		}
	}

	/** O que o nó de reserva pode assumir, e por que não (quando não pode) — para o admin não adivinhar. */
	record StatusView(String nodeId, boolean canTakeOver, int readyCopies, long failures, String reason,
			List<CopyView> copies) {

		static StatusView of(StandbySyncService.StandbyStatus status) {
			return new StatusView(status.nodeId(), status.canTakeOver(), status.readyCopies(), status.failures(),
				status.reason(), status.copies().stream().map(CopyView::of).toList());
		}
	}

	record CopyRequest(String nodeId) {
	}

	/**
	 * Pede a cópia AGORA. A resposta traz o início — o nó ainda não tem nada no disco. A conclusão
	 * chega quando o nó confirma o digest, e é ela que torna a cópia contável para assumir.
	 */
	@PostMapping("/authority/standby/copy")
	StatusView copy(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) CopyRequest request) {
		CurrentUser admin = admin(sessionToken);
		if (request == null) {
			throw new ApiException(400, "STANDBY_NODE_REQUIRED", "Informe o nó de reserva.");
		}
		standby.copyNow(admin.id().value(), request.nodeId());
		return StatusView.of(standby.status(request.nodeId()));
	}

	/**
	 * A sincronização completa ("a da madrugada"). Hoje é a mesma operação da cópia: o dump é
	 * consistente e a cópia do nó é atômica. O que ainda não existe — resto-binlog e dados de
	 * aplicação — está declarado na doc como lacuna, não como janela que o nó finge ter.
	 */
	@PostMapping("/authority/standby/full-sync")
	StatusView fullSync(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) CopyRequest request) {
		CurrentUser admin = admin(sessionToken);
		if (request == null) {
			throw new ApiException(400, "STANDBY_NODE_REQUIRED", "Informe o nó de reserva.");
		}
		standby.fullSync(admin.id().value(), request.nodeId());
		return StatusView.of(standby.status(request.nodeId()));
	}

	@GetMapping("/authority/standby")
	StatusView status(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String nodeId) {
		admin(sessionToken);
		if (nodeId == null || nodeId.isBlank()) {
			throw new ApiException(400, "STANDBY_NODE_REQUIRED", "Informe o nó de reserva.");
		}
		return StatusView.of(standby.status(nodeId));
	}

	/**
	 * Promove a autoridade COM a cópia do banco: a promoção do §4 do doc 06, mais a exigência que o
	 * failover precisa — o nó de reserva tem de ter o banco de quem manda.
	 *
	 * <p>Sem cópia completa, a cópia é pedida ANTES do termo subir e a promoção é recusada com 409.
	 * Não é burocracia: promover antes de copiar faria o novo controlador começar sem o estado do
	 * antigo, e isso não é failover, é recomeço.</p>
	 *
	 * <p>{@code instanceId} é a instância da autoridade (a do eleitorado, que escala em pod);
	 * {@code nodeId} é o nó da rede que guarda a cópia do banco. São coisas diferentes.</p>
	 */
	@PostMapping("/authority/promote-standby")
	PromotedView promoteWithStandby(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) PromoteStandbyRequest request) {
		CurrentUser admin = admin(sessionToken);
		if (request == null || request.instanceId() == null || request.instanceId().isBlank()) {
			throw new ApiException(400, "NETWORK_AUTHORITY_INSTANCE_REQUIRED", "Informe a instância a promover.");
		}
		if (request.nodeId() == null || request.nodeId().isBlank()) {
			throw new ApiException(400, "STANDBY_NODE_REQUIRED",
				"Informe o nó que guarda o banco: promover sem cópia recomeça do zero.");
		}
		var status = standby.status(request.nodeId());
		if (!status.canTakeOver()) {
			standby.copyNow(admin.id().value(), request.nodeId());
			throw new ApiException(409, "NETWORK_STANDBY_COPY_MISSING",
				"Este nó ainda não tem cópia completa do banco. A cópia foi pedida agora; confirme o estado antes de promover.");
		}
		var promoted = authorities.promote(admin.id().value(), request.instanceId(), request.reason());
		audit.record(admin.id().value(), "NETWORK_AUTHORITY_PROMOTED_WITH_STANDBY", request.nodeId(),
			"{\"term\":" + promoted.term() + ",\"authority\":\"" + request.instanceId().replace("\"", "") + "\"}");
		return PromotedView.of(promoted, status);
	}

	record PromoteStandbyRequest(String instanceId, String nodeId, String reason) {
	}

	record PromotedView(int term, String authorityId, Instant promotedAt, int readyCopies) {

		static PromotedView of(AuthorityRecord record, StandbySyncService.StandbyStatus status) {
			return new PromotedView(record.term(), record.authorityId(), record.promotedAt(), status.readyCopies());
		}
	}

	/**
	 * O dump para o admin DIAGNOSTICAR (não é o caminho do nó, que tem rota própria). Exige o nó
	 * explicitamente: sem ele não há cópia de quem ler, e adivinhar seria entregar o banco a partir do
	 * nó errado.
	 */
	@GetMapping("/authority/standby/dump")
	ResponseEntity<byte[]> dump(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String nodeId, @RequestParam(required = false) Long offset) {
		CurrentUser admin = admin(sessionToken);
		if (nodeId == null || nodeId.isBlank()) {
			throw new ApiException(400, "STANDBY_NODE_REQUIRED", "Informe o nó da cópia.");
		}
		long from = offset == null ? 0L : offset;
		StandbySyncService.Copy copy = standby.download(admin.id().value(), nodeId, from);
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
			.header("X-Standby-Byte-Start", Integer.toString(copy.byteStart()))
			.header("X-Standby-Total-Bytes", Integer.toString(copy.totalBytes()))
			.header("X-Standby-Sha256", copy.sha256())
			.body(copy.body());
	}

	private CurrentUser admin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}
}