package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkStateRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkTaskRepository;
import com.socialcoisa.rede.network.domain.NetworkMode;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.TaskStatus;
import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * Modo operacional da rede, calculado a partir de números medidos.
 *
 * <p>O critério é a fila contra a capacidade: quantas tarefas esperam e quantas vagas os nós vivos
 * oferecem agora. Se a fila passa do limiar de vagas, a rede entra em {@code HIGH_LOAD}; quando
 * volta abaixo do limiar de saída, volta a {@code NORMAL}. Os dois limiares são diferentes de
 * propósito — um limiar só faria o modo piscar a cada tarefa que entra e sai.</p>
 *
 * <p>O cálculo automático só mexe em {@code NORMAL} e {@code HIGH_LOAD}. {@code DEGRADED},
 * {@code EMERGENCY} e {@code RECOVERY} são decisão humana da fase 4, e sobrescrevê-los aqui seria a
 * automação desfazendo uma decisão de incidente.</p>
 */
@Service
public class NetworkModeService {

	private static final Logger LOG = LoggerFactory.getLogger(NetworkModeService.class);

	private final NetworkStateRepository state;
	private final NetworkTaskRepository tasks;
	private final NetworkNodeRepository nodes;
	private final AuditTrail audit;
	private final Clock clock;
	private final double enterRatio;
	private final double exitRatio;

	NetworkModeService(NetworkStateRepository state, NetworkTaskRepository tasks, NetworkNodeRepository nodes,
			AuditTrail audit, Clock clock,
			@Value("${rede.network.mode.high-load-enter-ratio:1.0}") double enterRatio,
			@Value("${rede.network.mode.high-load-exit-ratio:0.5}") double exitRatio) {
		this.state = state;
		this.tasks = tasks;
		this.nodes = nodes;
		this.audit = audit;
		this.clock = clock;
		this.enterRatio = enterRatio;
		this.exitRatio = exitRatio;
	}

	@Transactional(readOnly = true)
	public NetworkMode current() {
		return state.current().map(NetworkStateRepository.StateRecord::mode).orElse(NetworkMode.NORMAL);
	}

	/**
	 * Recalcula o modo. Devolve o modo vigente depois do cálculo.
	 *
	 * <p>A transição é auditada com o número que a motivou: sem isso, "entrou em HIGH_LOAD" não diz
	 * nada a quem investiga depois.</p>
	 */
	@Transactional
	public NetworkMode evaluate() {
		Instant now = clock.instant();
		NetworkMode current = current();
		if (!current.automatic()) {
			// Modo de incidente: a automação não desfaz decisão humana.
			return current;
		}
		long pending = tasks.countByStatus(TaskStatus.PENDING);
		long capacity = availableCapacity(now);
		double ratio = capacity == 0 ? (pending == 0 ? 0 : Double.MAX_VALUE) : (double) pending / capacity;
		NetworkMode next = current;
		if (current == NetworkMode.NORMAL && ratio >= enterRatio) {
			next = NetworkMode.HIGH_LOAD;
		}
		else if (current == NetworkMode.HIGH_LOAD && ratio <= exitRatio) {
			next = NetworkMode.NORMAL;
		}
		if (next != current) {
			String reason = "fila=" + pending + " vagas=" + capacity + " razao=" + String.format("%.2f", ratio);
			state.setMode(next, reason, now);
			audit.record("system", "NETWORK_MODE_" + next.name(), "global",
				"{\"pending\":" + pending + ",\"capacity\":" + capacity + "}");
			LOG.info("Modo da rede {} -> {} ({})", current, next, reason);
		}
		return next;
	}

	/** Vagas livres somadas dos nós vivos. Nó saturado não conta vaga. */
	private long availableCapacity(Instant now) {
		List<NetworkNode> candidates = nodes.findCandidates(now.minusSeconds(180), 200);
		long total = 0;
		for (NetworkNode node : candidates) {
			if (node.saturated()) {
				continue;
			}
			total += Math.max(0, node.concurrencyLimit() - node.runningTasks());
		}
		return total;
	}
}
