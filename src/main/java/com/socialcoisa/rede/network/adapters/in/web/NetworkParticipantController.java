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
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.NetworkNodeService;
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
	 *
	 * <p>O disco segue a mesma regra: {@code declaredDiskMb} é o que ele cedeu,
	 * {@code observedContentUsedMb} é o que o Énóis <b>ocupa</b> ali (o nó mede o próprio estado de
	 * conteúdo) e {@code reservedDiskMb} é o que ele guardou para si. Antes só o declarado vinha: o
	 * §18 pede "consultar espaço utilizado", e o participante — que é justamente quem controla a
	 * própria infraestrutura — não tinha como ver quanto ela está sendo usada.</p>
	 */
	record MyNodeView(String id, String displayName, String status, List<String> profiles, String region,
			String country, String runtimeVersion, Instant lastSeenAt, int declaredCpuCores, int declaredMemoryMb,
			int declaredDiskMb, int declaredBandwidthMbps, Double observedCpuPercent, Integer observedMemoryMb,
			Integer observedRunningTasks, Integer latencyMs, Long observedContentUsedMb, Double diskUsedPercent,
			Long reservedDiskMb, Long trafficInMb, Long trafficOutMb, Long trafficTotalMb,
			Double outboundSharePercent, int outages, int measuredOutages, long offlineSeconds,
			Long lastOfflineSeconds, boolean online, boolean availabilityComplete, long tasksDone, long tasksRequeued,
			boolean operable, String quarantineReason, Map<String, Integer> storageBandwidth) {

		static MyNodeView of(NetworkParticipantService.NodeWithContribution entry) {
			NetworkNode node = entry.node();
			boolean quarantined = node.quarantinedUntil() != null;
			return new MyNodeView(node.id(), node.displayName(), node.status().name(),
				node.profiles().stream().map(Enum::name).sorted().toList(), node.region(), node.country(),
				node.runtimeVersion(), node.lastSeenAt(), node.declaredCpuCores(), node.declaredMemoryMb(),
				node.declaredDiskMb(), node.declaredBandwidthMbps(), node.observedCpuPercent(),
				node.observedMemoryMb(), node.observedRunningTasks(), node.observedLatencyMs(),
				node.observedContentUsedMb(), node.usedPercent(), node.reservedDiskMb(),
				node.observedBytesInMb(), node.observedBytesOutMb(), node.observedTrafficMb(),
				node.outboundSharePercent(),
				entry.availability().outages(), entry.availability().measuredOutages(),
				entry.availability().offlineSeconds(), entry.availability().lastOfflineSeconds(),
				entry.availability().online(), entry.availability().complete(),
				entry.tasksDone(), entry.tasksRequeued(),
				node.canOperate(), quarantined ? node.quarantineReason() : null,
				// Nome do tipo em vez do enum: é o vocabulário do contrato (§6), o mesmo da rota.
				entry.storageBandwidth().entrySet().stream()
					.collect(java.util.stream.Collectors.toMap(entry2 -> entry2.getKey().name(),
						Map.Entry::getValue, (a, b) -> a, java.util.LinkedHashMap::new)));
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
	/**
	 * Pausa a participação (brief §18). O nó continua registrado e reportando; para de receber
	 * tarefa e réplica. Reversível por ele mesmo em {@code /resume} — por isso não revoga a chave,
	 * nem passa por {@code SUSPENDED}, que só um admin desfaz.
	 */
	@PostMapping("/nodes/{id}/pause")
	MyNodeView pause(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return MyNodeView.of(participant.pause(user.id().value(), id));
	}

	/** Retoma o que ele mesmo pausou. Só {@code PAUSED} volta por aqui. */
	@PostMapping("/nodes/{id}/resume")
	MyNodeView resume(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return MyNodeView.of(participant.resume(user.id().value(), id));
	}

	/** O que o participante cede neste nó. Campo ausente mantém o valor atual (brief §18). */
	record CapacityRequest(Integer cpuCores, Integer memoryMb, Integer diskMb, Integer bandwidthMbps) {
	}

	/**
	 * O participante ajusta os próprios limites — capacidade máxima e banda (brief §18).
	 *
	 * <p>Antes só o admin mudava capacidade: o participante precisava de um terceiro para emprestar
	 * mais (ou menos) da própria máquina. A escrita é a mesma de {@code /admin/.../capacity}, então
	 * a mudança compõe {@code configVersion} e o runtime aplica na próxima leitura — não existe
	 * caminho paralelo de configuração. O que continua exclusivo do admin é o perfil.</p>
	 */
	@PostMapping("/nodes/{id}/capacity")
	MyNodeView capacity(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id, @RequestBody(required = false) CapacityRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return MyNodeView.of(participant.capacity(user.id().value(), id,
			request == null ? null : request.cpuCores(), request == null ? null : request.memoryMb(),
			request == null ? null : request.diskMb(), request == null ? null : request.bandwidthMbps()));
	}

	/** Banda própria de um tipo de storage node (brief §6). {@code mbps} nulo remove. */
	record StorageBandwidthRequest(Integer mbps) {
	}

	/**
	 * O participante dá ao tipo de armazenamento um teto de banda próprio.
	 *
	 * <p>Existe porque no brief §6 a banda é por <b>tipo</b>, e um servidor pode ter vários: um
	 * nó ARCHIVE que cede 20 Mbps no total pode reservar 15 deles ao arquivo e deixar 5 para o
	 * convencional. O tipo tem de estar entre os perfis do nó — o serviço recusa caso contrário.</p>
	 */
	@PostMapping("/nodes/{id}/storage-bandwidth/{kind}")
	MyNodeView storageBandwidth(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id, @PathVariable String kind,
			@RequestBody(required = false) StorageBandwidthRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return MyNodeView.of(participant.storageBandwidth(user.id().value(), id, kind,
			request == null ? null : request.mbps()));
	}

	@PostMapping("/nodes/{id}/disconnect")
	MyNodeView disconnect(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		// A tela do participante mostra a contribuição; a desconexão devolve o mesmo quadro, e um
		// zero inventado aqui apagaria as tarefas que ele já concluiu no instante em que ele sai.
		return MyNodeView.of(participant.disconnect(user.id().value(), id));
	}

	/**
	 * Solicitação de ingresso: a rota que abre o onboarding sem passar pelo painel do admin. O
	 * token devolvido é de uso único e curto — o participante o leva ao instalador
	 * (deploy/node-runtime), que faz o enroll. Perfis não são escolhidos aqui: o participante
	 * declara o servidor, e o que ele pode executar é negociado com o admin na aprovação.
	 */
	record JoinRequest(String displayName) {
	}

	record JoinResponse(String token, Instant expiresAt, String nextStep) {
	}

	@PostMapping("/join")
	JoinResponse requestJoin(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) JoinRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		NetworkNodeService.IssuedToken issued = participant.requestJoin(user.id().value(),
			request == null ? null : request.displayName());
		return new JoinResponse(issued.token(), issued.expiresAt(),
			"Use o token no instalador do runtime (deploy/node-runtime). O no nasce em PENDING_APPROVAL.");
	}
}
