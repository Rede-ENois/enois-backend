package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.EdgeDeliverService;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Verificação de entrega de borda no painel. Admin, como o resto de {@code /admin/network}.
 *
 * <p>O caso é o imediato: o admin que viu divergência na reconciliação, ou que aprovou um nó
 * novo, pergunta àquele nó específico se ele entrega um conteúdo <b>agora</b> — e recebe o
 * veredicto na linha da tarefa, conferido contra o que o nó confirmou na origem. É a prova de
 * capacidade da borda sem expor porta nem abrir conexão para o nó.</p>
 *
 * <p>O pedido é por nó e por conteúdo, porque o corpo verificado é o do cache daquele nó.</p>
 */
@RestController
@RequestMapping("/admin/network/nodes")
class AdminNetworkEdgeDeliverController {

	private final EdgeDeliverService deliver;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	AdminNetworkEdgeDeliverController(EdgeDeliverService deliver, CurrentUserResolver currentUserResolver,
			AdminGuard adminGuard) {
		this.deliver = deliver;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	record EdgeDeliverRequestView(String taskId, String nodeId, String kind, String contentId, String status,
			int attempt, Instant deadline, Instant createdAt) {

		static EdgeDeliverRequestView of(NetworkTask task, String kind, String contentId) {
			return new EdgeDeliverRequestView(task.id(), task.targetNodeId(), kind, contentId, task.status().name(),
				task.attempt(), task.deadline(), task.createdAt());
		}
	}

	/**
	 * Enfileira a verificação de entrega deste conteúdo por este nó. Idempotente pela janela:
	 * pedir duas vezes na mesma janela devolve a tarefa que já existe em vez de criar outra.
	 */
	@PostMapping("/{nodeId}/edge-deliver")
	EdgeDeliverRequestView request(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String nodeId, @RequestParam String kind, @RequestParam String contentId) {
		CurrentUser admin = admin(sessionToken);
		return EdgeDeliverRequestView.of(deliver.request(nodeId, kind, contentId, admin.id().value()), kind,
			contentId);
	}

	/** Os pedidos de entrega em aberto do nó — o que ainda está na fila ou executando. */
	@GetMapping("/{nodeId}/edge-deliver")
	List<EdgeDeliverRequestView> open(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String nodeId) {
		admin(sessionToken);
		return deliver.openFor(nodeId).stream().map(task -> EdgeDeliverRequestView.of(task, null, null)).toList();
	}

	private CurrentUser admin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}
}