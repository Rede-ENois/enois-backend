package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.EdgeCacheService;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Aquecimento do cache de borda no painel. Admin, como o resto de {@code /admin/network}.
 *
 * <p>A rota existe porque a aquisição periódica não resolve o caso imediato: o admin que acabou de
 * aprovar um nó de borda, ou que viu {@code reset=true} no feed, precisa mandar aquele nó buscar o
 * conteúdo <b>agora</b> — esperar o ciclo seguinte seria deixar a borda fria sem motivo.</p>
 *
 * <p>O pedido é por nó, e não "para a rede", porque o cursor do cache vive no disco do nó: uma
 * tarefa sem alvo seria designada a um nó sorteado e os outros continuariam frios.</p>
 */
@RestController
@RequestMapping("/admin/network/nodes")
class AdminNetworkEdgeCacheController {

	private final EdgeCacheService edge;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	AdminNetworkEdgeCacheController(EdgeCacheService edge, CurrentUserResolver currentUserResolver,
			AdminGuard adminGuard) {
		this.edge = edge;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	record EdgeCacheRequestView(String taskId, String nodeId, String status, int attempt, Instant deadline,
			Instant createdAt) {

		static EdgeCacheRequestView of(NetworkTask task) {
			return new EdgeCacheRequestView(task.id(), task.targetNodeId(), task.status().name(), task.attempt(),
				task.deadline(), task.createdAt());
		}
	}

	/**
	 * Enfileira a aquisição de cache para este nó. Idempotente pela janela: pedir duas vezes na
	 * mesma janela devolve a tarefa que já existe em vez de criar outra.
	 */
	@PostMapping("/{nodeId}/edge-cache")
	EdgeCacheRequestView request(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String nodeId) {
		CurrentUser admin = admin(sessionToken);
		return EdgeCacheRequestView.of(edge.request(nodeId, admin.id().value()));
	}

	/** Os pedidos de cache em aberto do nó — o que ainda está na fila ou executando. */
	@GetMapping("/{nodeId}/edge-cache")
	List<EdgeCacheRequestView> open(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String nodeId) {
		admin(sessionToken);
		return edge.openFor(nodeId).stream().map(EdgeCacheRequestView::of).toList();
	}

	private CurrentUser admin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}
}