package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.NetworkParticipantService;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Interface do participante: a visão do dono do servidor sobre os próprios nós — estado, o que
 * cedeu, o que a rede observou no último heartbeat e a contribuição medida em tarefas concluídas.
 * Autenticado por sessão de navegador, não por chave de nó: quem fala aqui é a pessoa, não o
 * runtime.
 *
 * <p>A fronteira de exposição é {@code owner_user_id}: nenhuma rota aqui devolve nó de outro
 * participante, e nó alheio é 404 — não 403, que confirmaria que o id existe. A chave de API não
 * aparece: ela é emitida pelo admin, exibida uma única vez a ele, e o runtime a guarda.</p>
 */
@RestController
@RequestMapping("/network/participant")
class NetworkParticipantController {

	private final NetworkParticipantService participant;
	private final CurrentUserResolver currentUserResolver;

	NetworkParticipantController(NetworkParticipantService participant, CurrentUserResolver currentUserResolver) {
		this.participant = participant;
		this.currentUserResolver = currentUserResolver;
	}

	/**
	 * Um nó como o participante o vê. Capacidade declarada (o que ele aceitou ceder) ao lado da
	 * observada (o que o último heartbeat mediu) — o contrato pede que ele veja os dois, porque é
	 * comparando-os que ele ajusta os próprios limites.
	 */
	record MyNodeView(String id, String displayName, String status, List<String> profiles, String region,
			String country, String runtimeVersion, Instant lastSeenAt, int declaredCpuCores, int declaredMemoryMb,
			int declaredDiskMb, int declaredBandwidthMbps, Double observedCpuPercent, Integer observedMemoryMb,
			Integer observedRunningTasks, Integer latencyMs, long tasksDone, boolean operable,
			String quarantineReason) {

		static MyNodeView of(NetworkParticipantService.NodeWithContribution entry) {
			NetworkNode node = entry.node();
			boolean quarantined = node.quarantinedUntil() != null;
			return new MyNodeView(node.id(), node.displayName(), node.status().name(),
				node.profiles().stream().map(Enum::name).sorted().toList(), node.region(), node.country(),
				node.runtimeVersion(), node.lastSeenAt(), node.declaredCpuCores(), node.declaredMemoryMb(),
				node.declaredDiskMb(), node.declaredBandwidthMbps(), node.observedCpuPercent(),
				node.observedMemoryMb(), node.observedRunningTasks(), node.observedLatencyMs(),
				entry.tasksDone(),
				node.canOperate(), quarantined ? node.quarantineReason() : null);
		}
	}

	@GetMapping("/nodes")
	List<MyNodeView> myNodes(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return participant.myNodes(user.id().value()).stream().map(MyNodeView::of).toList();
	}

	@GetMapping("/nodes/{id}")
	MyNodeView myNode(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return MyNodeView.of(participant.myNode(user.id().value(), id));
	}

	/**
	 * Desconexão voluntária: o servidor sai do balanceamento e a chave deixa de autenticar na hora.
	 * A suspensão é reversível pelo admin — religar o nó do participante é decisão humana, não do
	 * runtime que "voltou a ligar".
	 */
	@PostMapping("/nodes/{id}/disconnect")
	MyNodeView disconnect(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return MyNodeView.of(new NetworkParticipantService.NodeWithContribution(
			participant.disconnect(user.id().value(), id), 0));
	}
}
