package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.StorageRebalanceService;
import com.socialcoisa.rede.network.domain.StorageRebalance;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Rebalanceamento do Storage pelo painel (brief §16).
 *
 * <p>Cada transferência é tráfego de corpo pela rede de quem participa, e uma delas move um arquivo
 * entre nós: é decisão de operador, nunca de agendador. Por isso as quatro rotas são admin, e a
 * remoção da cópia antiga exige a confirmação do destino — não há rota que "force" o passo 3.</p>
 */
@RestController
@RequestMapping("/admin/network/storage/rebalance")
class AdminNetworkStorageRebalanceController {

	private final StorageRebalanceService rebalances;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	AdminNetworkStorageRebalanceController(StorageRebalanceService rebalances,
			CurrentUserResolver currentUserResolver, AdminGuard adminGuard) {
		this.rebalances = rebalances;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	/** As transferências vivas, com a cópia antiga ainda no lugar. */
	@GetMapping
	List<View> active(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		admin(sessionToken);
		return rebalances.active().stream().map(View::of).toList();
	}

	/**
	 * Pede a transferência: a réplica {@code fromReplica} sai e a nova nasce em {@code toNodeId}.
	 * O objeto continua servível durante tudo, porque a antiga só sai no {@code complete}.
	 */
	@PostMapping
	View move(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) MoveRequest request) {
		CurrentUser admin = admin(sessionToken);
		if (request == null || request.objectId() == null || request.toNodeId() == null) {
			throw new ApiException(400, "STORAGE_REBALANCE_INVALID",
				"Informe o objeto, a replica de origem e o no de destino.");
		}
		StorageRebalance rebalance = rebalances.move(admin.id().value(), request.objectId(),
			request.fromReplica(), request.toNodeId(), request.bandwidthLimitMbps());
		// A tarefa sai na hora: o operador pediu a transferência, e deixá-la esperando o próximo tick
		// faria a rota parecer que não fez nada.
		rebalances.dispatch(request.objectId());
		return View.of(rebalance);
	}

	/** O destino confirmou? Então a cópia antiga sai. Sem confirmação, 409 e a antiga fica. */
	@PostMapping("/{id}/complete")
	View complete(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		CurrentUser admin = admin(sessionToken);
		return View.of(rebalances.complete(admin.id().value(), id));
	}

	/**
	 * As transferências <b>órfãs</b>: vivas, mas sem designação pendente do destino.
	 *
	 * <p>É a diferença entre "ainda a caminho" e "morreu no meio". A lista de {@code GET} mostra só as
	 * vivas; esta diz <b>quais</b> vivas não têm como terminar, para o operador fechar pelo
	 * {@code POST stale/release}.</p>
	 */
	@GetMapping("/stale")
	List<View> stale(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		admin(sessionToken);
		return rebalances.stale().stream().map(View::of).toList();
	}

	/**
	 * Fecha as órfãs como {@code FAILED}, sem tocar a cópia antiga. O padrão é <b>seco</b>.
	 *
	 * <p>Mesmo desenho de {@code /storage/pending/release}: a leitura é segura e a escrita é ato do
	 * operador. Fechar é irreversível na direção do {@code DONE} — transferência {@code FAILED} não
	 * completa mais —, então quem decide é quem leu a lista. Não recolhe a designação, não completa,
	 * não apaga corpo e não mexe na cópia antiga.</p>
	 */
	@PostMapping("/stale/release")
	StorageRebalanceService.Release releaseStale(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
		CurrentUser admin = admin(sessionToken);
		return rebalances.releaseStale(admin.id().value(), dryRun);
	}

	/** Desfaz antes da confirmação; a cópia antiga nunca é tocada. */
	@PostMapping("/{id}/cancel")
	View cancel(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id, @RequestBody(required = false) CancelRequest request) {
		CurrentUser admin = admin(sessionToken);
		return View.of(rebalances.cancel(admin.id().value(), id, request == null ? null : request.reason()));
	}

	private CurrentUser admin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}

	record MoveRequest(String objectId, int fromReplica, String toNodeId, Integer bandwidthLimitMbps) {
	}

	record CancelRequest(String reason) {
	}

	record View(String id, String objectId, int fromReplica, String fromNodeId, int toReplica, String toNodeId,
			String state, String failureReason, long bytes, Integer bandwidthLimitMbps, Instant createdAt,
			Instant finishedAt) {

		static View of(StorageRebalance rebalance) {
			return new View(rebalance.id(), rebalance.objectId(), rebalance.fromReplica(), rebalance.fromNodeId(),
				rebalance.toReplica(), rebalance.toNodeId(), rebalance.state().name(), rebalance.failureReason(),
				rebalance.bytes(), rebalance.bandwidthLimitMbps(), rebalance.createdAt(), rebalance.finishedAt());
		}
	}
}