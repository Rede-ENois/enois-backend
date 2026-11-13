package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.NetworkModeService;
import com.socialcoisa.rede.network.application.NetworkTaskService;
import com.socialcoisa.rede.network.application.NodeKeyService;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Rotas de tarefa que um nó chama. A credencial é a API key do nó ({@code X-Node-Id} +
 * {@code X-Node-Key}), conferida em {@link NodeKeyService} — não há sessão de navegador aqui.
 *
 * <p>O modelo é <b>pull</b>: o nó pede o que deve executar em {@code GET /network/tasks/next} e
 * devolve o resultado em {@code POST /network/tasks/{taskId}/result}. O Control Plane nunca abre
 * conexão para o nó, então um participante atrás de NAT funciona e não existe URL de terceiro
 * virando requisição nossa.</p>
 *
 * <p>O nó não escolhe tarefa nem muda o próprio estado: ele recebe o que foi designado a ele e
 * reporta o que aconteceu. Toda decisão de designação é do Control Plane.</p>
 */
@RestController
@RequestMapping("/network/tasks")
class NetworkTaskController {

	private final NetworkTaskService tasks;
	private final NetworkModeService modes;
	private final NodeKeyService keys;

	NetworkTaskController(NetworkTaskService tasks, NetworkModeService modes, NodeKeyService keys) {
		this.tasks = tasks;
		this.modes = modes;
		this.keys = keys;
	}

	record TaskView(String taskId, String type, int attempt, String deadline, String idempotencyKey, String payload,
			String dataClass) {

		static TaskView of(NetworkTask task) {
			return new TaskView(task.id(), task.taskType().name(), task.attempt(), task.deadline().toString(),
				task.idempotencyKey(), task.payload(), task.dataClass().name());
		}
	}

	record NextResponse(List<TaskView> tasks, String mode, String serverTime) {
	}

	record ResultRequest(String taskId, int attempt, String status, String output, ErrorBody error) {
	}

	record ErrorBody(String code, String message, Boolean retryable) {
	}

	record ResultResponse(String taskId, String status, boolean applied, String reason) {
	}

	/**
	 * O que este nó deve executar agora. Devolve lista vazia quando não há nada — não é erro, é o nó
	 * perguntando e não tendo trabalho.
	 */
	@GetMapping("/next")
	NextResponse next(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		List<TaskView> assigned = tasks.nextFor(node).stream().map(TaskView::of).toList();
		return new NextResponse(assigned, modes.current().name(), Instant.now().toString());
	}

	/**
	 * Resultado de uma tarefa. {@code applied=false} com {@code reason} diz que o resultado foi
	 * descartado (tentativa antiga, tarefa já concluída, nó revogado no meio) — o nó não deve
	 * reenviar.
	 *
	 * <p>É a única rota que aceita a chave de um nó <b>revogado</b>, e só para descartar: o resultado
	 * que chega depois da revogação é registrado em auditoria com o nó de origem e nunca aplicado
	 * (doc 06 §5). Sem isso o descarte prometido sumia num 401 sem rastro.</p>
	 */
	@PostMapping("/{taskId}/result")
	ResultResponse result(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey, @PathVariable String taskId,
			@RequestBody(required = false) ResultRequest request) {
		NodeKeyService.ResultCredential credential = keys.authenticateForResult(nodeId, nodeKey);
		if (request == null) {
			throw new ApiException(400, "TASK_RESULT_REQUIRED", "Corpo do resultado ausente.");
		}
		boolean done = "DONE".equalsIgnoreCase(request.status());
		boolean failed = "FAILED".equalsIgnoreCase(request.status());
		if (!done && !failed) {
			throw new ApiException(400, "TASK_STATUS_INVALID", "Status do resultado invalido.");
		}
		ErrorBody error = request.error();
		var command = new NetworkTaskService.ResultCommand(taskId, request.attempt(), done, request.output(),
			error == null ? null : error.message(), error != null && Boolean.TRUE.equals(error.retryable()));
		var outcome = credential.revoked() ? tasks.discardRevokedResult(credential.node(), command)
				: tasks.acceptResult(credential.node(), command);
		return new ResultResponse(outcome.taskId(), outcome.status(), outcome.applied(), outcome.reason());
	}

	/** Configuração que o nó aplica: perfis, modo vigente e o contrato. Assinatura entra na fase 5. */
	@GetMapping("/config")
	ConfigResponse config(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		return new ConfigResponse(node.id(), node.profiles().stream().map(Enum::name).sorted().toList(),
			modes.current().name(), "1", Instant.now().toString());
	}

	record ConfigResponse(String nodeId, List<String> profiles, String mode, String contract, String issuedAt) {
	}
}
