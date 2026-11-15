package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.ApiRequestService;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Requisição a API auxiliar no painel. Admin, como o resto de {@code /admin/network}.
 *
 * <p>O admin pede pelo <b>nome</b> da rota, nunca pela URL: o destino está na config assinada que
 * o nó recebeu, e o runtime resolve o nome contra ela. Por isso a rota não aceita parâmetro de
 * URL — um destino escrito aqui entraria na fila como string que o nó não sabe resolver, e a task
 * morreria como "rota não autorizada".</p>
 *
 * <p>A requisição é por nó: o pedido é sobre aquele nó, e a task leva alvo, para não ser executada
 * em outro.</p>
 */
@RestController
@RequestMapping("/admin/network")
class AdminNetworkApiRequestController {

	private final ApiRequestService api;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;
	private final ObjectMapper objectMapper;

	AdminNetworkApiRequestController(ApiRequestService api, CurrentUserResolver currentUserResolver,
			AdminGuard adminGuard, ObjectMapper objectMapper) {
		this.api = api;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
		this.objectMapper = objectMapper;
	}

	record ApiRequestView(String taskId, String nodeId, String route, String status, int attempt, Instant deadline,
			Instant createdAt) {

		static ApiRequestView of(NetworkTask task, String route) {
			return new ApiRequestView(task.id(), task.targetNodeId(), route, task.status().name(), task.attempt(),
				task.deadline(), task.createdAt());
		}
	}

	/**
	 * Enfileira a requisição à rota, para este nó. Idempotente pela janela: pedir duas vezes na mesma
	 * janela devolve a tarefa que já existe em vez de criar outra.
	 */
	@PostMapping("/nodes/{nodeId}/api-request")
	ApiRequestView request(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String nodeId,
			@RequestParam("route") String route) {
		CurrentUser admin = admin(sessionToken);
		NetworkTask task = api.request(nodeId, route, admin.id().value());
		return ApiRequestView.of(task, routeOf(task.payload()));
	}

	/** As requisições em aberto do nó — o que ainda está na fila ou executando. */
	@GetMapping("/nodes/{nodeId}/api-request")
	List<ApiRequestView> open(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String nodeId) {
		admin(sessionToken);
		return api.openFor(nodeId).stream()
			.map(task -> ApiRequestView.of(task, routeOf(task.payload())))
			.toList();
	}

	/** As rotas que a rede oferece — o painel pede um nome que exista na lista assinada. */
	@GetMapping("/api-routes")
	List<String> routes(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		admin(sessionToken);
		return api.availableRoutes();
	}

	/**
	 * O nome da rota que a task carrega. O painel mostra o nome — nunca a URL, que só existe na
	 * config assinada que o nó recebe. Payload ilegível devolve {@code null}: a linha continua
	 * listada com o que se sabe (estado, tentativa, prazo), e o painel não inventa rota.
	 */
	private String routeOf(String payload) {
		if (payload == null) {
			return null;
		}
		try {
			JsonNode root = objectMapper.readTree(payload);
			JsonNode route = root == null ? null : root.get("route");
			return route != null && route.isString() && !route.asString().isBlank() ? route.asString() : null;
		}
		catch (RuntimeException malformed) {
			return null;
		}
	}

	private CurrentUser admin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}
}
