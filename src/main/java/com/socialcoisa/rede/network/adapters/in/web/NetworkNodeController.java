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
import com.socialcoisa.rede.network.application.NetworkRegionService;
import com.socialcoisa.rede.network.application.NodeKeyService;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkRegions;
import com.socialcoisa.rede.network.domain.NodeStatus;
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
	private final NetworkRegionService regions;
	private final com.socialcoisa.rede.network.application.NodeObservabilityService observability;
	private final com.socialcoisa.rede.network.application.NodeCertificateService certificates;

	NetworkNodeController(NetworkNodeService nodes, NodeKeyService keys, NetworkModeService modes,
			NetworkConfigService configs, NetworkRegionService regions,
			com.socialcoisa.rede.network.application.NodeObservabilityService observability,
			com.socialcoisa.rede.network.application.NodeCertificateService certificates) {
		this.nodes = nodes;
		this.keys = keys;
		this.modes = modes;
		this.configs = configs;
		this.regions = regions;
		this.observability = observability;
		this.certificates = certificates;
	}

	/**
	 * A região pode vir aninhada em {@code location} — como o contrato em 03-contratos.md sempre
	 * documentou — ou solta em {@code region}/{@code country}. Antes desta rodada só a forma solta
	 * era lida: um instalador que seguia a doc gravava o nó sem região, e o enroll aceitava em
	 * silêncio.
	 */
	/**
	 * {@code csr} é o pedido de certificado (PEM ou base64), opcional: o nó que não o manda continua
	 * entrando pelo token, e recebe o certificado depois com a chave pela rota de renovação
	 * ({@code POST /network/nodes/me/certificate}). Mandar no enroll evita um segundo round-trip logo
	 * no primeiro contato — o momento em que o nó ainda está sem credencial nenhuma.
	 */
	record EnrollRequest(String provisionToken, String runtimeVersion, Declared declared, String region,
			String country, Location location, String csr) {

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

	/**
	 * {@code certificate} só vem preenchido quando o enroll trouxe CSR <em>e</em> a CA está
	 * configurada. {@code caPublicKey} é o pino que o nó guarda para conferir o próprio certificado
	 * e a config assinada.
	 */
	record EnrollResponse(String nodeId, String status, String contract, String message, String certificate,
			String caPublicKey, String certificateSerial, String certificateNotAfter) {
	}

	record HeartbeatRequest(String runtimeVersion, Observed observed, Integer observedTerm,
			java.util.List<LogRequest> logs) {
	}

	/**
	 * O que o nó mediu. {@code memoryMb} é memória <b>em uso</b> e {@code memoryAvailableMb} a
	 * <b>disponível</b> — o runtime anterior à V380 mandava o disponível em {@code memoryMb}, e o
	 * Control Plane deriva o uso dele (ver {@code NetworkNode.usageFrom}). Os dois nulos são "não
	 * medido", nunca zero: zero de uso afirmaria máquina ociosa.
	 *
	 * <p>{@code bytesInMb}/{@code bytesOutMb} são o tráfego ACUMULADO nas interfaces
	 * dele (brief §18, "consultar tráfego"); o Control Plane só recebe a contagem e não soma nada
	 * por conta própria — quem transfere é a máquina do participante, e é ela que mede.
	 */
	record Observed(Double cpuPercent, Integer memoryMb, Integer memoryAvailableMb, Integer runningTasks,
			Long diskFreeMb, Long diskTotalMb, Long usedMb, Long bytesInMb, Long bytesOutMb) {
	}

	/**
	 * Linha do console do nó. O runtime manda o que já escreveria no log dele; o Control Plane guarda
	 * uma janela curta para a página do nó, e nada além disso (não é auditoria nem retenção).
	 */
	record LogRequest(String level, String message, String at) {
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
		// O certificado NÃO autoriza nada por si: sem a API key o nó continua sem operar (doc 04 §1).
		// Emiti-lo aqui só poupa o round-trip; a autorização continua sendo a chave.
		com.socialcoisa.rede.network.application.NodeCertificateService.IssuedCertificate issued = null;
		if (request.csr() != null && !request.csr().isBlank() && certificates.enabled()) {
			// O "ator" da auditoria é o próprio nó (o enroll é autenticado pelo token de uso único),
			// não um admin: a emissão no enroll é o nó pedindo o próprio certificado.
			issued = certificates.issue(node.id(), request.csr(), node.id());
		}
		return new EnrollResponse(node.id(), node.status().name(), "1",
			"Aguarde a chave de API e a aprovacao do administrador.",
			issued == null ? null : issued.certificatePem(), issued == null ? null : issued.caPublicKeyBase64(),
			issued == null ? null : issued.serial(), issued == null ? null : issued.notAfter());
	}

	/**
	 * Renova o certificado do nó (brief §10, "rotação"): o nó gera um par novo, manda o CSR e recebe
	 * o certificado. É a mesma porta para o nó que entrou sem CSR no enroll.
	 *
	 * <p>A autenticação é a API key — a credencial que <b>autoriza</b>. O certificado que o nó já
	 * tem não é exigido aqui de propósito: quem vai renovar pode estar com o certificado a um dia de
	 * expirar, e exigir o certificado velho é como pedir a senha antiga a quem quer trocá-la.</p>
	 */
	@PostMapping("/nodes/me/certificate")
	com.socialcoisa.rede.network.application.NodeCertificateService.IssuedCertificate renewCertificate(
			@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey,
			@RequestBody(required = false) CertificateRequest request) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		if (request == null || request.csr() == null || request.csr().isBlank()) {
			throw new ApiException(400, "NODE_CSR_MISSING", "CSR ausente.");
		}
		// O ator é o próprio nó: a renovação não é decisão administrativa, é manutenção da máquina.
		return certificates.issue(node.id(), request.csr(), node.id());
	}

	record CertificateRequest(String csr) {
	}

	/**
	 * O endereço de origem visto na borda (brief §5, "detecção de mudança de IP").
	 *
	 * <p>É observação do Control Plane, não alegação do nó: o participante doméstico não informa IP
	 * (o §5 lista "informar IP no painel" entre o que ele não deve precisar fazer) e, atrás de NAT,
	 * o endereço muda sem que a máquina saiba. {@code X-Forwarded-For} é usado quando existe porque
	 * atrás do proxy o socket fala com o proxy, não com o nó; o primeiro item da lista é o cliente.
	 * Sem nenhum dos dois, devolve {@code null} — "não medido", nunca um endereço inventado.</p>
	 */
	private static String peerAddressFromRequest() {
		var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
		if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
			return servlet.getRequest().getRemoteAddr();
		}
		return null;
	}

	private static String peerIp(String forwardedFor, String remoteAddr) {
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			String first = forwardedFor.split(",")[0].trim();
			if (!first.isEmpty() && !"unknown".equalsIgnoreCase(first)) {
				return first;
			}
		}
		return remoteAddr == null || remoteAddr.isBlank() ? null : remoteAddr;
	}

	@PostMapping("/nodes/heartbeat")
	HeartbeatResponse heartbeat(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey,
			@RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
			@RequestBody(required = false) HeartbeatRequest request) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		Observed observed = request == null ? null : request.observed();
		NetworkNode updated = nodes.heartbeat(node, new NetworkNodeService.HeartbeatCommand(
			request == null ? null : request.runtimeVersion(), observed == null ? null : observed.cpuPercent(),
			observed == null ? null : observed.memoryMb(), observed == null ? null : observed.memoryAvailableMb(),
			observed == null ? null : observed.runningTasks(),
			observed == null ? null : observed.diskFreeMb(), observed == null ? null : observed.diskTotalMb(),
			observed == null ? null : observed.usedMb(), observed == null ? null : observed.bytesInMb(),
			observed == null ? null : observed.bytesOutMb(), request == null ? null : request.observedTerm(),
			peerIp(forwardedFor, peerAddressFromRequest())));
		// acceptTasks também é falso quando o PRÓPRIO nó está em quarentena de status: é assim que
		// o nó de TERM_CONFLICT drena na hora, sem precisar de 403 — 403 no heartbeat encerraria o
		// canal pelo qual ele reporta o termo que viu.
		//
		// E falso quando a REGIÃO do nó foi evacuada: o nó está ACTIVE e continua autenticando, mas a
		// região não opera. Dizer `true` a ele seria a evacuação existindo só no painel — o nó
		// pediria tarefa e receberia lista vazia em loop, em vez de drenar e parar de perguntar.
		// O quadro ao vivo da página do nó é alimentado por este mesmo heartbeat: a amostra do
		// minuto e as linhas que o runtime escreveu. O nó não abre porta (brief §17), então ele
		// EMPURRA a observação em vez de o Control Plane abrir conexão para dentro da máquina de
		// quem participa. Nada disto bloqueia a resposta do heartbeat: uma amostra que falhe não
		// pode impedir um nó de receber permissão de operar.
		if (observed != null) {
			// O uso vem do nó JÁ derivado (`updated`), não do campo cru: para o runtime antigo o
			// `memoryMb` do corpo é o DISPONÍVEL, e passar o cru gravaria a máquina ociosa com 7,9 GB
			// em uso na série histórica — o mesmo defeito que a derivação corrige em `network_nodes`.
			observability.record(updated, observed.cpuPercent(), updated.observedMemoryMb(), observed.diskFreeMb(),
				observed.runningTasks());
		}
		if (request != null && request.logs() != null) {
			for (LogRequest line : request.logs()) {
				if (line == null) {
					continue;
				}
				observability.log(updated, line.level(), line.message(), parseInstant(line.at()));
			}
		}
		boolean regionEvacuated = NetworkRegions.outOfService(regions.outOfService(), updated.region());
		boolean acceptTasks = modes.current().dispatchAllowed() && updated.status() == NodeStatus.ACTIVE
				&& !regionEvacuated;
		return new HeartbeatResponse(updated.id(), updated.status().name(),
			updated.profiles().stream().map(Enum::name).sorted().toList(), Instant.now().toString(),
			modes.current().name(), acceptTasks);
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

	/** Instante do runtime; ausente ou ilegível cai no relógio do Control Plane. */
	private static java.time.Instant parseInstant(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return java.time.Instant.parse(raw.strip());
		}
		catch (java.time.format.DateTimeParseException unreadable) {
			return null;
		}
	}
}
