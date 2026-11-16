package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkTaskRepository;
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkRegions;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.security.Hashes;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Produtor das requisições a API auxiliar (fase 5, {@code NODE_API}).
 *
 * <p>O executor {@code API_REQUEST} e a lista de rotas assinada já existiam; o que não existia era
 * <b>quem enfileira</b>. Sem produtor, o executor era código morto: a rota estava assinada, o nó
 * sabia resolver o nome, e nada nunca pedia nada.</p>
 *
 * <p>O produtor <b>não escolhe o destino</b>. Ele escolhe o <b>nome</b> da rota, e o nome só vale se
 * estiver na lista assinada que o nó recebeu — a conferência é feita aqui, contra a mesma lista
 * que o runtime vai receber, e não contra uma segunda leitura da propriedade de configuração. A
 * task nasce, portanto, sempre executável: um nome fora da lista seria uma task fadada ao
 * {@code API_ROUTE_NOT_ALLOWED}, ou seja, trabalho morto com prazo.</p>
 *
 * <p>Nem a task nem o pedido levam URL, cabeçalho ou credencial. O que sai daqui é o nome da rota e
 * o método ({@code GET} ou {@code HEAD}); o runtime resolve o nome contra a assinatura, segue
 * <b>sem</b> redirecionamento e <b>sem</b> a credencial do nó, e devolve só evidência
 * ({@code status}, {@code bytes}, {@code digest}) — nunca o corpo.</p>
 *
 * <p>A task é enfileirada <b>por nó</b>, com alvo: o pedido do admin é sobre aquele nó, e o
 * destino da rota pode estar ligado ao estado local dele (o cache de respostas). Executar no nó
 * errado é não executar.</p>
 */
@Service
public class ApiRequestService {

	private final NetworkTaskService tasks;
	private final NetworkTaskRepository taskRepository;
	private final NetworkNodeRepository nodes;
	private final NetworkConfigService configs;
	private final NetworkRegionService regions;
	private final AuditTrail audit;
	private final Clock clock;
	private final int windowMinutes;
	private final int deadlineMinutes;

	ApiRequestService(NetworkTaskService tasks, NetworkTaskRepository taskRepository, NetworkNodeRepository nodes,
			NetworkConfigService configs, NetworkRegionService regions, AuditTrail audit, Clock clock,
			@Value("${rede.network.api.request.window-minutes:5}") int windowMinutes,
			@Value("${rede.network.api.request.deadline-minutes:10}") int deadlineMinutes) {
		this.tasks = tasks;
		this.taskRepository = taskRepository;
		this.nodes = nodes;
		this.configs = configs;
		this.regions = regions;
		this.audit = audit;
		this.clock = clock;
		this.windowMinutes = Math.max(1, windowMinutes);
		this.deadlineMinutes = Math.max(1, deadlineMinutes);
	}

	/**
	 * Enfileira a requisição à rota pedida, para o nó escolhido pelo admin.
	 *
	 * <p>A ordem das recusas é a que o admin consegue ler: primeiro o que ele errou (nó, rota), depois
	 * o que o nó não pode fazer agora (perfil, operação, região). Nenhuma task nasce fadada: um nó
	 * sem o perfil de API, fora de operação ou numa região evacuada é recusado <b>aqui</b>, e não
	 * deixado para a fila.</p>
	 *
	 * @param nodeId nó que vai executar a requisição
	 * @param route nome da rota, como está na lista assinada
	 */
	@Transactional
	public NetworkTask request(String nodeId, String route, String actorId) {
		if (nodeId == null || nodeId.isBlank()) {
			throw new ApiException(400, "NODE_ID_REQUIRED", "Informe o no.");
		}
		if (route == null || route.isBlank()) {
			throw new ApiException(400, "API_ROUTE_REQUIRED", "Informe o nome da rota.");
		}
		String routeName = route.trim();
		NetworkNode node = nodes.findById(nodeId.trim())
			.orElseThrow(() -> new ApiException(404, "NODE_UNKNOWN", "No nao encontrado."));
		// O mesmo predicado que o despacho usa (`TaskType.allowedFor`): se o nó não pode executar
		// API_REQUEST, a task ficaria na fila até o prazo.
		if (!TaskType.API_REQUEST.allowedFor(node.profiles())) {
			throw new ApiException(422, "NODE_PROFILE_REQUIRED", "No sem perfil de API nao faz requisicao a API.");
		}
		if (!node.status().canOperate()) {
			throw new ApiException(409, "NODE_NOT_OPERABLE", "No fora de operacao nao executa tarefa.");
		}
		// Região evacuada: o nó está ACTIVE e autentica, mas a região não opera. A task tem alvo e
		// ficaria na fila até o prazo.
		if (NetworkRegions.outOfService(regions.outOfService(), node.region())) {
			throw new ApiException(409, "NODE_REGION_OUT_OF_SERVICE",
				"Regiao do no fora de operacao: a requisicao volta quando a regiao voltar.");
		}
		// A rota precisa estar na lista que o nó recebe assinada. Conferir aqui evita a task que
		// morre no nó como "rota nao autorizada" — o produtor e o runtime olham a mesma lista.
		if (!configs.allowsRoute(routeName)) {
			throw new ApiException(422, "API_ROUTE_NOT_ALLOWED",
				"Rota fora da lista assinada: declare a rota na configuracao da rede.");
		}
		NetworkTask task = enqueueFor(node, routeName, clock.instant());
		audit.record(actorId, "NETWORK_API_REQUEST_REQUESTED", node.id(),
			"{\"taskId\":\"" + task.id() + "\",\"route\":\"" + routeName + "\"}");
		return task;
	}

	/**
	 * As requisições em aberto do nó — o que o painel mostra e o que o executor vai buscar.
	 */
	@Transactional(readOnly = true)
	public List<NetworkTask> openFor(String nodeId) {
		return taskRepository.findPendingTargeted(nodeId, clock.instant(), 20).stream()
			.filter(task -> task.taskType() == TaskType.API_REQUEST)
			.toList();
	}

	/**
	 * As rotas que a rede oferece, para o painel não pedir um nome que não existe.
	 */
	@Transactional(readOnly = true)
	public List<String> availableRoutes() {
		return configs.allowedRouteNames();
	}

	/**
	 * A chave é {@code api:<hash>}: o hash cobre nó, rota e janela, e 68 caracteres cabem nos 80 da
	 * coluna.
	 *
	 * <p><b>Por nó e por rota</b>, porque duas rotas no mesmo nó são trabalhos diferentes (uma pode
	 * ser a vitrine e a outra o preço), e o mesmo nome em nós diferentes também. <b>Por janela</b>,
	 * porque a mesma rota precisa ser requisitada de novo depois — sem a janela a chave seria eterna
	 * e a requisição só aconteceria uma vez na vida do nó.</p>
	 */
	private NetworkTask enqueueFor(NetworkNode node, String route, Instant now) {
		String window = Long.toString(now.getEpochSecond() / (windowMinutes * 60L));
		String idempotency = "api:" + Hashes.sha256Hex(node.id() + ":" + route + ":" + window);
		// Só o nome e o método: o destino vem da assinatura, e nada aqui é URL nem credencial. O
		// nome da rota já é o mesmo conjunto que o runtime aceita (`[A-Za-z0-9._-]+`), então o JSON
		// não precisa de escape — e o payload não pode conter a sequência que o runtime usa para
		// recortar (`","dataClass":"`).
		String payload = "{\"route\":\"" + route + "\",\"method\":\"GET\"}";
		Instant deadline = now.plus(deadlineMinutes, ChronoUnit.MINUTES);
		return tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.API_REQUEST, idempotency, payload,
			DataClass.PUBLIC, null, deadline, node.id()));
	}
}
