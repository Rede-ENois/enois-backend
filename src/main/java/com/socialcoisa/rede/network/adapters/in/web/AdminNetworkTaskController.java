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

import com.socialcoisa.rede.network.application.NetworkModeService;
import com.socialcoisa.rede.network.application.NetworkTaskService;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.TaskStatus;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Tarefas distribuídas no painel. Exclusivo do admin, como o resto de {@code /admin/network}.
 *
 * <p>A DLQ é visível aqui de propósito: tarefa que esgotou as tentativas continua na tabela com o
 * último erro, e o admin precisa vê-la para decidir entre reprocessar e investigar. Esconder a
 * tarefa morta seria esconder o problema.</p>
 */
@RestController
@RequestMapping("/admin/network/tasks")
class AdminNetworkTaskController {

	private final NetworkTaskService tasks;
	private final NetworkModeService modes;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	AdminNetworkTaskController(NetworkTaskService tasks, NetworkModeService modes,
			CurrentUserResolver currentUserResolver, AdminGuard adminGuard) {
		this.tasks = tasks;
		this.modes = modes;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	/**
	 * A tarefa como o painel a vê. {@code region} é a preferência de origem do pedido (null =
	 * sem preferência); ela aparece porque é a única explicação visível de por que uma tarefa foi
	 * para um nó distante — e porque o admin precisa poder conferir que uma tarefa regional não
	 * está presa esperando a região.
	 */
	record TaskView(String id, String type, String status, int attempt, String dataClass, String region,
			String assignedNodeId, Instant deadline, Instant nextAttemptAt, Instant finishedAt, String lastError,
			Instant createdAt, String targetNodeId) {

		static TaskView of(NetworkTask task) {
			return new TaskView(task.id(), task.taskType().name(), task.status().name(), task.attempt(),
				task.dataClass().name(), task.region(), task.assignedNodeId(), task.deadline(),
				task.nextAttemptAt(), task.finishedAt(), task.lastError(), task.createdAt(), task.targetNodeId());
		}
	}

	record OverviewView(String mode, long pending, long dispatched, long done, long failed, long dead,
			long cancelled, long awaitingDrain) {
	}

	@GetMapping
	List<TaskView> list(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "200") int limit) {
		admin(sessionToken);
		return tasks.list(limit).stream().map(TaskView::of).toList();
	}

	/**
	 * Contadores por estado e o modo vigente: é o que a tela mostra no topo.
	 *
	 * <p>{@code awaitingDrain} é quanto do {@code dispatched} está preso a nó fora de operação. É
	 * aditivo: a tela antiga ignora o campo, e o número existe porque a drenagem só roda na
	 * varredura — entre a suspensão e o ciclo seguinte o admin precisa ver que há trabalho parado.</p>
	 */
	@GetMapping("/overview")
	OverviewView overview(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		admin(sessionToken);
		return new OverviewView(modes.current().name(), tasks.countByStatus(TaskStatus.PENDING),
			tasks.countByStatus(TaskStatus.DISPATCHED), tasks.countByStatus(TaskStatus.DONE),
			tasks.countByStatus(TaskStatus.FAILED), tasks.countByStatus(TaskStatus.DEAD),
			tasks.countByStatus(TaskStatus.CANCELLED), tasks.countOnNonOperatingNodes());
	}

	@GetMapping("/{id}")
	TaskView byId(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		admin(sessionToken);
		return TaskView.of(tasks.require(id));
	}

	@PostMapping("/{id}/cancel")
	TaskView cancel(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		CurrentUser admin = admin(sessionToken);
		return TaskView.of(tasks.cancel(id, admin.id().value()));
	}

	private CurrentUser admin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}
}
