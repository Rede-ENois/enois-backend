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
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Modo operacional da rede, calculado a partir de números medidos.
 *
 * <p>O critério é a fila contra a capacidade: quantas tarefas esperam e quantas vagas os nós vivos
 * oferecem agora. Se a fila passa do limiar de vagas, a rede entra em {@code HIGH_LOAD}; quando
 * volta abaixo do limiar de saída, volta a {@code NORMAL}. Os dois limiares são diferentes de
 * propósito — um limiar só faria o modo piscar a cada tarefa que entra e sai.</p>
 *
 * <p>{@code DEGRADED} e {@code EMERGENCY} são decisão humana: dependência crítica instável não é
 * algo que o Control Plane mede, e o cálculo automático nunca os desfaz — automação desfazendo uma
 * decisão de incidente é como o incidente volta. {@code RECOVERY} começa por decisão humana (ou por
 * saída de {@code HIGH_LOAD}, quando configurado) e <b>termina por número</b>: fila abaixo do
 * limiar de saída devolve {@code NORMAL}, senão a recuperação viraria um modo esquecido.</p>
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
	 * Transição por decisão humana. Só os modos de incidente e de recuperação são acionáveis à mão —
	 * {@code NORMAL} e {@code HIGH_LOAD} pertencem ao cálculo medido, e um admin "forçando NORMAL"
	 * com a fila cheia seria a automação readmitindo na próxima varredura com aparência de decisão.
	 *
	 * <p>A transição é auditada com o ator, o modo anterior e o motivo declarado; exigir motivo é o
	 * que impede "ativei EMERGENCY para ver o que acontece" sem registro.</p>
	 */
	@Transactional
	public NetworkMode declare(String actorId, NetworkMode next, String declaredReason) {
		if (next == null || !next.manual()) {
			throw new ApiException(400, "NETWORK_MODE_NOT_MANUAL",
				"Modo sem acionamento manual: NORMAL e HIGH_LOAD seguem o calculo medido.");
		}
		if (declaredReason == null || declaredReason.isBlank()) {
			throw new ApiException(400, "NETWORK_MODE_REASON_REQUIRED", "Declare o motivo da transicao.");
		}
		NetworkMode current = current();
		if (current == next) {
			return current;
		}
		String reason = "manual por " + actorId + ": " + declaredReason.trim();
		state.setMode(next, reason, clock.instant());
		audit.record(actorId, "NETWORK_MODE_" + next.name(), "global",
			"{\"from\":\"" + current.name() + "\",\"reason\":\"" + escape(declaredReason.trim()) + "\"}");
		LOG.info("Modo da rede {} -> {} ({})", current, next, reason);
		return next;
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
			// Modo de incidente humano: a automação não desfaz decisão.
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
		else if (current == NetworkMode.RECOVERY && ratio <= exitRatio) {
			// RECOVERY termina por número: recuperação que ninguém encerra vira modo esquecido, com o
			// despacho pela metade para sempre.
			next = NetworkMode.NORMAL;
		}
		if (next != current) {
			// Sem capacidade a razão é infinita, e formatar Double.MAX_VALUE com duas casas gera mais
			// de 300 caracteres — a coluna reason é varchar(200) e a transição falharia exatamente no
			// caso de rede sem vaga. O texto diz o que importa sem inventar número.
			String reason = "fila=" + pending + " vagas=" + capacity + " razao="
				+ (capacity == 0 ? "sem-vagas" : String.format("%.2f", ratio));
			state.setMode(next, reason, now);
			audit.record("system", "NETWORK_MODE_" + next.name(), "global",
				"{\"pending\":" + pending + ",\"capacity\":" + capacity + "}");
			LOG.info("Modo da rede {} -> {} ({})", current, next, reason);
		}
		return next;
	}

	/** Motivo entra em metadata JSON: aspas e barra do texto humano não podem quebrar o JSON. */
	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/**
	 * Vagas livres somadas dos nós vivos. Nó saturado não conta vaga, e nó fora do balanceamento
	 * também não: contar a vaga de quem não vai receber a próxima tarefa faria a razão fila/vagas
	 * parecer folgada e a rede nunca entrar em {@code HIGH_LOAD} justamente quando está com menos
	 * capacidade do que aparenta.
	 */
	private long availableCapacity(Instant now) {
		List<NetworkNode> candidates = nodes.findCandidates(now.minusSeconds(180), 200);
		long total = 0;
		for (NetworkNode node : candidates) {
			if (node.saturated() || node.quarantined(now)) {
				continue;
			}
			total += Math.max(0, node.concurrencyLimit() - node.runningTasks());
		}
		return total;
	}
}
