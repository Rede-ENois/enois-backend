package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.NetworkConfigService;
import com.socialcoisa.rede.network.application.NetworkModeService;
import com.socialcoisa.rede.network.application.NetworkNodeService;
import com.socialcoisa.rede.network.application.NodeKeyService;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Rotas que um nó da rede chama. Não usam {@code X-Session-Token}: a credencial é a API key do nó
 * ({@code X-Node-Id} + {@code X-Node-Key}), conferida em {@link NodeKeyService}.
 *
 * <p>Só existem duas coisas aqui na fase 1 — o enroll (autenticado pelo token de provisionamento de
 * uso único) e o heartbeat. Tarefas entram na fase 2. Nada aqui devolve permissão que o nó não
 * tenha: o payload é o próprio estado para o runtime decidir drenar ou parar.</p>
 */
@RestController
@RequestMapping("/network")
class NetworkNodeController {

	private final NetworkNodeService nodes;
	private final NodeKeyService keys;
	private final NetworkModeService modes;
	private final NetworkConfigService configs;

	NetworkNodeController(NetworkNodeService nodes, NodeKeyService keys, NetworkModeService modes,
			NetworkConfigService configs) {
		this.nodes = nodes;
		this.keys = keys;
		this.modes = modes;
		this.configs = configs;
	}

	/**
	 * A região pode vir aninhada em {@code location} — como o contrato em 03-contratos.md sempre
	 * documentou — ou solta em {@code region}/{@code country}. Antes desta rodada só a forma solta
	 * era lida: um instalador que seguia a doc gravava o nó sem região, e o enroll aceitava em
	 * silêncio.
	 */
	record EnrollRequest(String provisionToken, String runtimeVersion, Declared declared, String region,
			String country, Location location) {

		String effectiveRegion() {
			return firstNonBlank(region, location == null ? null : location.region());
		}

		String effectiveCountry() {
			return firstNonBlank(country, location == null ? null : location.country());
		}

		private static String firstNonBlank(String a, String b) {
			return a != null && !a.isBlank() ? a : b;
		}
	}

	record Location(String region, String country) {
	}

	record Declared(int cpuCores, int memoryMb, int diskMb, int bandwidthMbps) {
	}

	record EnrollResponse(String nodeId, String status, String contract, String message) {
	}

	record HeartbeatRequest(String runtimeVersion, Observed observed) {
	}

	record Observed(Double cpuPercent, Integer memoryMb, Integer runningTasks) {
	}

	/**
	 * O modo vigente vai no heartbeat porque é assim que o nó sabe drenar: em {@code DEGRADED} e
	 * {@code EMERGENCY} ele para de pedir tarefa nova e termina o que tem na mão; em {@code RECOVERY}
	 * sabe que está voltando. Sem isso o nó executaria no escuro e a contingência existiria só no
	 * painel.
	 */
	record HeartbeatResponse(String nodeId, String status, List<String> profiles, String serverTime,
			String mode, boolean acceptTasks) {
	}

	/**
	 * Cria o nó a partir do token de provisionamento. Não devolve chave: a API key é emitida pelo
	 * admin depois do enroll, e o nó só opera depois da aprovação.
	 */
	@PostMapping("/nodes/enroll")
	EnrollResponse enroll(@RequestBody(required = false) EnrollRequest request) {
		if (request == null) {
			throw new ApiException(400, "NODE_TOKEN_INVALID", "Corpo do enroll ausente.");
		}
		Declared declared = request.declared();
		NetworkNode node = nodes.enroll(new NetworkNodeService.EnrollCommand(request.provisionToken(),
			request.effectiveRegion(), request.effectiveCountry(), declared == null ? 0 : declared.cpuCores(),
			declared == null ? 0 : declared.memoryMb(), declared == null ? 0 : declared.diskMb(),
			declared == null ? 0 : declared.bandwidthMbps()));
		return new EnrollResponse(node.id(), node.status().name(), "1",
			"Aguarde a chave de API e a aprovacao do administrador.");
	}

	@PostMapping("/nodes/heartbeat")
	HeartbeatResponse heartbeat(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey,
			@RequestBody(required = false) HeartbeatRequest request) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		Observed observed = request == null ? null : request.observed();
		NetworkNode updated = nodes.heartbeat(node, new NetworkNodeService.HeartbeatCommand(
			request == null ? null : request.runtimeVersion(), observed == null ? null : observed.cpuPercent(),
			observed == null ? null : observed.memoryMb(), observed == null ? null : observed.runningTasks()));
		return new HeartbeatResponse(updated.id(), updated.status().name(),
			updated.profiles().stream().map(Enum::name).sorted().toList(), Instant.now().toString(),
			modes.current().name(), modes.current().dispatchAllowed());
	}

	/**
	 * A permissão do nó, assinada. Lê com a chave vigente mesmo em {@code PENDING_APPROVAL}: o
	 * runtime precisa saber que ainda não pode operar, e 403 aqui deixaria o instalador sem
	 * resposta justamente enquanto ele espera a aprovação humana. A config diz isso em
	 * {@code operable:false} e lista de tarefas vazia — o mesmo critério do scheduler, dito ao nó.
	 */
	@GetMapping("/nodes/me/config")
	NetworkConfigService.NodeConfigView myConfig(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey) {
		return configs.forNode(keys.authenticate(nodeId, nodeKey));
	}
}
