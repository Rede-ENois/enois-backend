package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkTaskRepository;
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkMode;
import com.socialcoisa.rede.network.domain.NetworkRegions;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.TaskStatus;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Scheduler distribuído: escolhe o nó, designa a tarefa, aceita o resultado e devolve à fila o que
 * ficou órfão.
 *
 * <p>O desenho é <b>pull</b>: o Control Plane designa a tarefa a um nó e o nó busca o que é dele em
 * {@code GET /network/tasks/next}. Nenhuma conexão sai do Control Plane para o nó. Três motivos, e
 * os três são de segurança: (1) um participante atrás de NAT não precisa abrir porta de entrada;
 * (2) não existe URL fornecida por terceiro virando requisição nossa, então não há superfície de
 * SSRF no despacho; (3) o nó só recebe o que ele mesmo pediu, com a credencial dele.</p>
 *
 * <p>O que garante "sem duplicar efeito" não é o nó: é o {@code attempt}. A tarefa tem id estável e
 * a tentativa sobe a cada designação; o resultado carrega a tentativa que o nó executou, e resultado
 * de tentativa antiga é descartado. Um nó que caiu no meio e voltou tarde não sobrescreve o
 * resultado de quem terminou.</p>
 */
@Service
public class NetworkTaskService {

	private static final Logger LOG = LoggerFactory.getLogger(NetworkTaskService.class);

	/** Teto de tarefas por varredura: uma rodada não pode segurar a tabela. */
	public static final int SCAN_LIMIT = 50;
	private static final int MAX_LIST = 200;
	private static final int MAX_ERROR = 500;

	private final NetworkTaskRepository tasks;
	private final NetworkNodeRepository nodes;
	/*
	 * ObjectProvider e nao injecao direta: o verificador de midia enfileira tarefas por este
	 * servico, entao a dependencia direta fecharia um ciclo. Ausente, a tarefa de midia NAO conclui
	 * (fail-closed) — nunca "aceita porque nao deu para conferir".
	 */
	private final org.springframework.beans.factory.ObjectProvider<MediaResultVerifier> mediaResults;
	/*
	 * Mesmo padrao da midia: a conferencia so vale para EDGE_DELIVER, e o provider permite que a
	 * implementacao nao exista sem quebrar o scheduler. Ausente, a entrega NAO conclui.
	 */
	private final org.springframework.beans.factory.ObjectProvider<com.socialcoisa.rede.network.application.port.out.EdgeDeliverVerifier> edgeDeliveries;
	private final NetworkModeService modes;
	private final NetworkRegionService regions;
	private final AuditTrail audit;
	private final Clock clock;
	private final Duration claimTtl;
	private final Duration nodeStaleAfter;
	private final int maxAttempts;
	private final Duration defaultDeadline;
	private final Duration quarantineWindow;

	NetworkTaskService(NetworkTaskRepository tasks, NetworkNodeRepository nodes, org.springframework.beans.factory.ObjectProvider<MediaResultVerifier> mediaResults,
			org.springframework.beans.factory.ObjectProvider<com.socialcoisa.rede.network.application.port.out.EdgeDeliverVerifier> edgeDeliveries,
			NetworkModeService modes, NetworkRegionService regions, AuditTrail audit, Clock clock,
			@Value("${rede.network.task.claim-ttl-seconds:120}") long claimTtlSeconds,
			@Value("${rede.network.task.node-stale-seconds:180}") long nodeStaleSeconds,
			@Value("${rede.network.task.max-attempts:5}") int maxAttempts,
			@Value("${rede.network.task.default-deadline-seconds:900}") long defaultDeadlineSeconds,
			@Value("${rede.network.balancing.quarantine-seconds:600}") long quarantineSeconds) {
		this.tasks = tasks;
		this.nodes = nodes;
		this.mediaResults = mediaResults;
		this.edgeDeliveries = edgeDeliveries;
		this.modes = modes;
		this.regions = regions;
		this.audit = audit;
		this.clock = clock;
		this.claimTtl = Duration.ofSeconds(Math.max(10, claimTtlSeconds));
		this.nodeStaleAfter = Duration.ofSeconds(Math.max(30, nodeStaleSeconds));
		this.maxAttempts = Math.max(1, maxAttempts);
		this.defaultDeadline = Duration.ofSeconds(Math.max(60, defaultDeadlineSeconds));
		this.quarantineWindow = Duration.ofSeconds(Math.max(30, quarantineSeconds));
	}

	/**
	 * Cria a tarefa na fila. A chave de idempotência é única: a mesma chave não cria duas tarefas,
	 * então um evento de outbox reprocessado não duplica trabalho.
	 */
	@Transactional
	public NetworkTask enqueue(EnqueueCommand command) {
		if (command == null || command.taskType() == null) {
			throw new ApiException(400, "TASK_TYPE_REQUIRED", "Informe o tipo da tarefa.");
		}
		if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
			throw new ApiException(400, "TASK_IDEMPOTENCY_REQUIRED", "Informe a chave de idempotencia.");
		}
		Instant now = clock.instant();
		Instant deadline = command.deadline() != null ? command.deadline() : now.plus(defaultDeadline);
		if (!deadline.isAfter(now)) {
			throw new ApiException(400, "TASK_EXPIRED", "Prazo da tarefa ja venceu.");
		}
		// A chave de idempotência é a trava contra duplicar trabalho: o mesmo evento reprocessado
		// devolve a tarefa que já existe em vez de criar outra.
		Optional<NetworkTask> existing = tasks.findByIdempotencyKey(command.idempotencyKey().trim());
		if (existing.isPresent()) {
			return existing.get();
		}
		String id = NodeIds.next("tk");
		tasks.create(id, command.taskType(), command.idempotencyKey().trim(),
			command.payload() == null ? "{}" : command.payload(),
			command.dataClass() == null ? DataClass.PUBLIC : command.dataClass(), command.region(),
			command.targetNodeId(), deadline, now, now);
		return tasks.findById(id)
			.orElseThrow(() -> new IllegalStateException("Tarefa recem-criada nao encontrada: " + id));
	}

	/**
	 * Uma rodada do scheduler: devolve à fila o que ficou órfão e designa o que está na fila.
	 *
	 * <p>Chamado pelo handler do outbox e pela varredura periódica. É idempotente por construção: as
	 * transições são condicionais, então duas instâncias rodando ao mesmo tempo não designam a mesma
	 * tarefa duas vezes.</p>
	 *
	 * @return quantas tarefas foram designadas nesta rodada
	 */
	@Transactional
	public int dispatchPending() {
		Instant now = clock.instant();
		// Recuperar órfã roda SEMPRE, inclusive em DEGRADED/EMERGENCY: devolver à fila a tarefa cujo
		// claim venceu não é designar trabalho novo, e deixá-la parada em DISPATCHED para sempre
		// perderia a tarefa no prazo em vez de esperar o modo voltar.
		reclaimAbandoned(now);
		NetworkMode mode = modes.current();
		int limit = mode.dispatchLimit(SCAN_LIMIT);
		if (limit == 0) {
			return 0;
		}
		List<NetworkTask> pending = tasks.findDispatchable(now, limit);
		if (pending.isEmpty()) {
			return 0;
		}
		List<NetworkNode> candidates = nodes.findCandidates(now.minus(nodeStaleAfter), MAX_LIST);
		// Regiões evacuadas são lidas UMA vez por rodada, como os candidatos: a escolha de cada tarefa
		// consulta a mesma resposta, e uma declaração que chegue no meio da rodada vale na próxima.
		List<String> evacuated = regions.outOfService();
		// As vagas lidas do heartbeat valem para o instante da leitura, não para a rodada inteira. Sem
		// este contador, dez tarefas na fila iam todas para o mesmo nó de quatro núcleos: cada escolha
		// relia a lista de candidatos, que ainda dizia "zero rodando", e a concorrência declarada pelo
		// participante era ignorada justamente quando havia trabalho para distribuir. O mapa é local da
		// rodada — quem de fato executa continua sendo o nó, e o próximo heartbeat dele corrige a conta.
		Map<String, Integer> reserved = new HashMap<>();
		int dispatched = 0;
		for (NetworkTask task : pending) {
			Optional<NetworkNode> chosen = choose(task, candidates, evacuated, now, reserved);
			if (chosen.isEmpty()) {
				// Sem nó elegível a tarefa fica na fila: o próximo ciclo tenta de novo. Não é erro —
				// é a rede sem capacidade agora, e matar a tarefa seria perder trabalho.
				LOG.debug("Tarefa {} sem no elegivel agora", task.id());
				continue;
			}
			NetworkNode node = chosen.get();
			if (tasks.claim(task.id(), node.id(), task.attempt(), now.plus(claimTtl), now) == 1) {
				reserved.merge(node.id(), 1, Integer::sum);
				dispatched++;
			}
		}
		return dispatched;
	}

	/**
	 * Devolve à fila as tarefas cujo claim venceu: o nó caiu no meio. A tentativa sobe, então o
	 * resultado que chegar depois é descartado.
	 */
	@Transactional
	public int reclaimAbandoned(Instant now) {
		int reclaimed = 0;
		for (NetworkTask task : tasks.findAbandoned(now, SCAN_LIMIT)) {
			if (task.expired(now)) {
				tasks.kill(task.id(), task.attempt(), "TASK_EXPIRED: prazo vencido com o no designado",
					now);
				audit.record("system", "NETWORK_TASK_EXPIRED", task.id(), "{\"attempt\":" + task.attempt() + "}");
				continue;
			}
			if (task.attempt() >= maxAttempts) {
				tasks.kill(task.id(), task.attempt(), "TASK_ABANDONED: no nao devolveu resultado em "
					+ maxAttempts + " tentativas", now);
				audit.record("system", "NETWORK_TASK_DEAD", task.id(), "{\"attempt\":" + task.attempt() + "}");
				continue;
			}
			if (tasks.requeue(task.id(), task.attempt(), "TASK_ABANDONED: claim vencido", now, now) == 1) {
				reclaimed++;
				audit.record("system", "NETWORK_TASK_REQUEUED", task.id(),
					"{\"attempt\":" + task.attempt() + ",\"reason\":\"claim_expired\"}");
			}
		}
		return reclaimed;
	}

	/**
	 * Drenagem: devolve à fila, na hora, o que estava designado a nós que perderam o direito de
	 * operar (suspenso, em quarentena, revogado).
	 *
	 * <p>Sem esta rodada a tarefa ficava retida até o claim vencer — dois minutos no padrão — por um
	 * nó que já não autentica: {@code reclaimAbandoned} só olha claim <b>vencido</b>, e o nó suspenso
	 * não vai bater heartbeat nem devolver resultado nesse intervalo. Suspender um nó para manutenção
	 * passava a custar uma pausa de claim TTL na fila inteira daquele nó.</p>
	 *
	 * <p>A devolução é segura porque o {@code attempt} sobe: o resultado do nó drenado, se um dia
	 * chegar, é de tentativa antiga e cai na regra de descarte que já existe. Drenar não é executar
	 * diferente — é só parar de esperar por quem não pode responder.</p>
	 */
	@Transactional
	public int drainSuspendedNodes() {
		Instant now = clock.instant();
		int drained = 0;
		for (NetworkTask task : tasks.findOnNonOperatingNodes(now, SCAN_LIMIT)) {
			if (task.expired(now)) {
				tasks.kill(task.id(), task.attempt(), "TASK_EXPIRED: prazo vencido no no drenado", now);
				audit.record("system", "NETWORK_TASK_EXPIRED", task.id(), "{\"attempt\":" + task.attempt() + "}");
				continue;
			}
			// Volta para a fila para já: o nó de origem está fora, então não há motivo para esperar o
			// backoff de falha — quem falhou foi a capacidade, não a tarefa.
			if (tasks.requeue(task.id(), task.attempt(), "TASK_DRAINED: no fora de operacao", now, now) == 1) {
				drained++;
				audit.record("system", "NETWORK_TASK_DRAINED", task.id(),
					"{\"attempt\":" + task.attempt() + ",\"node\":\"" + escape(task.assignedNodeId()) + "\"}");
			}
		}
		return drained;
	}

	/**
	 * Drenagem regional: devolve à fila, na hora, o que estava designado a nós de regiões evacuadas
	 * (V304).
	 *
	 * <p>Antes desta rodada, evacuar uma região não devolvia nada: a drenagem existente pergunta
	 * pelo <b>status do nó</b> ({@code n.status <> 'ACTIVE'}), e o nó da região caída continua
	 * {@code ACTIVE} — ele não foi suspenso, a região é que saiu. O resultado era a tarefa presa até
	 * o claim vencer (dois minutos no padrão), devolvida por {@code reclaimAbandoned}, e então
	 * designada de novo... ao mesmo nó, porque ele seguia elegível. A evacuação impede a designação
	 * nova; esta rodada impede a espera pela que já estava na mão dele.</p>
	 *
	 * <p>A devolução é segura pelo mesmo motivo da drenagem por status: o {@code attempt} sobe na
	 * próxima designação, e o resultado do nó evacuado, se um dia chegar, é de tentativa antiga e cai
	 * na regra de descarte que já existe.</p>
	 *
	 * <p>Tarefa com <b>alvo</b> num nó evacuado volta para a fila mas não é designada a outro: alvo é
	 * vínculo (V302), não preferência — o estado daquele trabalho vive naquele nó. Ela espera o
	 * prazo ou a região voltar, o que é o comportamento certo: executar no nó errado não é executar
	 * pior, é não executar.</p>
	 */
	@Transactional
	public int drainEvacuatedRegions() {
		List<String> evacuated = regions.outOfService();
		if (evacuated.isEmpty()) {
			return 0;
		}
		Instant now = clock.instant();
		int drained = 0;
		for (NetworkTask task : tasks.findOnEvacuatedRegions(evacuated, now, SCAN_LIMIT)) {
			if (task.expired(now)) {
				tasks.kill(task.id(), task.attempt(), "TASK_EXPIRED: prazo vencido em regiao evacuada", now);
				audit.record("system", "NETWORK_TASK_EXPIRED", task.id(), "{\"attempt\":" + task.attempt() + "}");
				continue;
			}
			// Volta para já: a capacidade daquela região não vai responder, e esperar o claim não
			// muda isso. Quem pode executar é outra região, e ela está pronta agora.
			if (tasks.requeue(task.id(), task.attempt(), "TASK_DRAINED: regiao fora de operacao", now, now) == 1) {
				drained++;
				audit.record("system", "NETWORK_TASK_REGION_EVACUATED", task.id(),
					"{\"attempt\":" + task.attempt() + ",\"node\":\"" + escape(task.assignedNodeId())
						+ "\",\"region\":\"" + escape(regionOf(task.assignedNodeId())) + "\"}");
			}
		}
		return drained;
	}

	/** A região do nó designado, para a auditoria dizer QUAL região drenou a tarefa. */
	private String regionOf(String nodeId) {
		if (nodeId == null) {
			return "";
		}
		return nodes.findById(nodeId).map(NetworkNode::region).orElse("");
	}

	/** Quantas tarefas estão presas a nó fora de operação — o painel mostra sem paginar. */
	@Transactional(readOnly = true)
	public long countOnNonOperatingNodes() {
		return tasks.countOnNonOperatingNodes(clock.instant());
	}

	/** Quantas tarefas estão presas a região evacuada — o painel mostra sem paginar. */
	@Transactional(readOnly = true)
	public long countOnEvacuatedRegions() {
		return tasks.countOnEvacuatedRegions(regions.outOfService(), clock.instant());
	}

	/**
	 * O que este nó deve executar agora. Só devolve tarefa designada a ele e com claim vigente; o nó
	 * não escolhe tarefa, ele recebe a que o Control Plane designou.
	 */
	@Transactional
	public List<NetworkTask> nextFor(NetworkNode node) {
		requireOperable(node);
		// Região evacuada não começa trabalho novo. O heartbeat já responde `acceptTasks=false` e é o
		// canal principal (drenar é não pedir, sem 403 que encerraria o reporte); esta checagem é a
		// segunda porta: um runtime que insista em pedir — ou que tenha o heartbeat em cache — não
		// recebe tarefa. Devolver lista vazia, e não 403, mantém o mesmo contrato da rota.
		if (NetworkRegions.outOfService(regions.outOfService(), node.region())) {
			return List.of();
		}
		return tasks.findAssignedTo(node.id(), clock.instant(), SCAN_LIMIT);
	}

	/**
	 * Descarta, com auditoria, o resultado que um nó revogado no meio da tarefa mandou depois
	 * (doc 06 §5).
	 *
	 * <p>A tarefa já saiu dele: a drenagem a devolveu à fila quando o status virou {@code REVOKED},
	 * então o resultado é de tentativa antiga e não pode ser aplicado. O que faltava era o registro —
	 * a autenticação recusava a chave revogada antes de chegar aqui, e o descarte prometido em
	 * auditoria nunca era gravado. O nó perdia o resultado e a operação perdia a explicação.</p>
	 *
	 * <p>Aqui nada é aplicado, em nenhum caso: a resposta é sempre {@code applied=false}. Revogação é
	 * terminal, e o resultado de um nó revogado não volta a mover tarefa — só documenta o que
	 * aconteceu.</p>
	 */
	@Transactional
	public ResultOutcome discardRevokedResult(NetworkNode node, ResultCommand command) {
		if (command == null || command.taskId() == null || command.taskId().isBlank()) {
			throw new ApiException(400, "TASK_ID_REQUIRED", "Informe a tarefa.");
		}
		NetworkTask task = tasks.findById(command.taskId())
			.orElseThrow(() -> new ApiException(404, "TASK_UNKNOWN", "Tarefa nao encontrada."));
		audit.record(node.id(), "NETWORK_TASK_RESULT_DISCARDED", task.id(), "{\"reason\":\"NODE_REVOKED\","
			+ "\"reportedAttempt\":" + command.attempt() + ",\"currentAttempt\":" + task.attempt()
			+ ",\"node\":\"" + escape(node.id()) + "\"}");
		return new ResultOutcome(task.id(), task.status().name(), false, "NODE_REVOKED");
	}

	/**
	 * Aceita o resultado de uma tarefa.
	 *
	 * <p>Descarta, sem erro para o nó, o resultado que não é da tentativa vigente: o nó pode ter
	 * terminado depois de o claim vencer e a tarefa já ter sido redistribuída. Aceitar esse resultado
	 * seria aplicar duas vezes o efeito de uma tarefa.</p>
	 */
	@Transactional
	public ResultOutcome acceptResult(NetworkNode node, ResultCommand command) {
		requireOperable(node);
		if (command == null || command.taskId() == null || command.taskId().isBlank()) {
			throw new ApiException(400, "TASK_ID_REQUIRED", "Informe a tarefa.");
		}
		NetworkTask task = tasks.findById(command.taskId())
			.orElseThrow(() -> new ApiException(404, "TASK_UNKNOWN", "Tarefa nao encontrada."));
		Instant now = clock.instant();
		if (task.status().terminal()) {
			// Já concluída: o nó está reenviando. Não é erro do nó, e não muda nada.
			return new ResultOutcome(task.id(), task.status().name(), false, "TASK_ALREADY_CLAIMED");
		}
		// A tentativa é conferida ANTES do dono da tarefa. O nó que caiu no meio e voltou tarde já
		// não é o designado — a tarefa foi redistribuída —, e o resultado dele tem de ser descartado
		// com auditoria, não recusado como se fosse um nó estranho tentando executar tarefa alheia.
		if (task.status() == TaskStatus.PENDING || !task.matchesAttempt(command.attempt())) {
			// Claim venceu (a tarefa voltou para a fila) ou a tentativa é antiga: a tarefa já foi
			// redistribuída. Descartar é o que impede o efeito duplo.
			audit.record(node.id(), "NETWORK_TASK_RESULT_DISCARDED", task.id(),
				"{\"reportedAttempt\":" + command.attempt() + ",\"currentAttempt\":" + task.attempt() + "}");
			return new ResultOutcome(task.id(), task.status().name(), false, "TASK_ATTEMPT_STALE");
		}
		if (!task.assignedTo(node.id())) {
			throw new ApiException(403, "TASK_NOT_ALLOWED", "Tarefa nao designada a este no.");
		}
		if (command.done()) {
			// Mídia não se aceita pela declaração do nó: o arquivo entregue é medido e comparado com
			// o tamanho e o hash que o nó declarou. Os demais tipos não passam por aqui, e por isso
			// continuam exatamente como estavam.
			if (task.taskType() == TaskType.MEDIA_TRANSCODE) {
				MediaResultVerifier verifier = mediaResults.getIfAvailable();
				MediaResultVerifier.Verification verification = verifier == null
					? MediaResultVerifier.Verification.refused("MEDIA_VERIFIER_UNAVAILABLE",
						"Conferencia de midia indisponivel.", true)
					: verifier.verify(task, command.output());
				if (!verification.ok()) {
					audit.record(node.id(), "NETWORK_TASK_RESULT_REFUSED", task.id(),
						"{\"status\":\"" + verification.status() + "\"}");
					if (verification.retryable() && task.attempt() < maxAttempts) {
						Instant next = now.plus(backoff(task.attempt()));
						tasks.requeue(task.id(), command.attempt(), verification.status(), next, now);
						return new ResultOutcome(task.id(), TaskStatus.PENDING.name(), false, verification.status());
					}
					tasks.fail(task.id(), command.attempt(), verification.status(), now);
					return new ResultOutcome(task.id(), TaskStatus.FAILED.name(), false, verification.status());
				}
			}
			// Entrega de borda tambem nao se aceita pela declaracao do no: o veredicto e conferido
			// contra o que ele mesmo confirmou na origem (snapshot de /synced).
			if (task.taskType() == TaskType.EDGE_DELIVER) {
				com.socialcoisa.rede.network.application.port.out.EdgeDeliverVerifier verifier = edgeDeliveries
					.getIfAvailable();
				com.socialcoisa.rede.network.application.port.out.EdgeDeliverVerifier.Verification verification = verifier == null
					? com.socialcoisa.rede.network.application.port.out.EdgeDeliverVerifier.Verification
						.refused("EDGE_DELIVER_VERIFIER_UNAVAILABLE", "Conferencia de entrega indisponivel.", true)
					: verifier.verify(task, command.output());
				if (!verification.ok()) {
					audit.record(node.id(), "NETWORK_TASK_RESULT_REFUSED", task.id(),
						"{\"status\":\"" + verification.status() + "\"}");
					if (verification.retryable() && task.attempt() < maxAttempts) {
						Instant next = now.plus(backoff(task.attempt()));
						tasks.requeue(task.id(), command.attempt(), verification.status(), next, now);
						return new ResultOutcome(task.id(), TaskStatus.PENDING.name(), false, verification.status());
					}
					tasks.fail(task.id(), command.attempt(), verification.status(), now);
					return new ResultOutcome(task.id(), TaskStatus.FAILED.name(), false, verification.status());
				}
			}
			if (tasks.complete(task.id(), command.attempt(), command.output(), now) == 1) {
				// Só o resultado APLICADO conta como saúde: um resultado descartado não é prova de que
				// o nó entregou nada.
				recordOutcome(node, true, task.dispatchedAt(), now);
				return new ResultOutcome(task.id(), TaskStatus.DONE.name(), true, null);
			}
			return new ResultOutcome(task.id(), task.status().name(), false, "TASK_ALREADY_CLAIMED");
		}
		// Falha reportada pelo nó: retentável volta para a fila com backoff; não retentável morre.
		// É aqui que a saúde é medida: o nó declarou que a execução falhou. Refusão de verificação
		// (acima) também indica defeito, mas o motivo é outro e é auditado no próprio fluxo.
		recordOutcome(node, false, null, now);
		String error = truncate(command.error() == null ? "TASK_FAILED" : command.error());
		if (task.taskType() == TaskType.MEDIA_TRANSCODE) {
			MediaResultVerifier verifier = mediaResults.getIfAvailable();
			if (verifier != null) {
				verifier.recordFailure(task, error, command.retryable());
			}
		}
		if (command.retryable() && task.attempt() < maxAttempts) {
			Instant next = now.plus(backoff(task.attempt()));
			tasks.requeue(task.id(), command.attempt(), error, next, now);
			return new ResultOutcome(task.id(), TaskStatus.PENDING.name(), true, null);
		}
		tasks.fail(task.id(), command.attempt(), error, now);
		return new ResultOutcome(task.id(), TaskStatus.FAILED.name(), true, null);
	}

	/** Cancela uma tarefa que ainda não terminou. Cancelar o que já terminou é 409 confesso. */
	@Transactional
	public NetworkTask cancel(String taskId, String actorId) {
		NetworkTask task = tasks.findById(taskId)
			.orElseThrow(() -> new ApiException(404, "TASK_UNKNOWN", "Tarefa nao encontrada."));
		if (task.status().terminal()) {
			throw new ApiException(409, "TASK_ALREADY_FINISHED", "Tarefa ja terminou.");
		}
		if (tasks.cancel(taskId, actorId, clock.instant()) == 0) {
			throw new ApiException(409, "TASK_ALREADY_FINISHED", "Tarefa ja terminou.");
		}
		audit.record(actorId, "NETWORK_TASK_CANCELLED", taskId, "{}");
		return tasks.findById(taskId).orElseThrow();
	}

	@Transactional(readOnly = true)
	public NetworkTask require(String taskId) {
		return tasks.findById(taskId)
			.orElseThrow(() -> new ApiException(404, "TASK_UNKNOWN", "Tarefa nao encontrada."));
	}

	@Transactional(readOnly = true)
	public List<NetworkTask> list(int limit) {
		return tasks.listRecent(Math.min(Math.max(limit, 1), MAX_LIST));
	}

	@Transactional(readOnly = true)
	public long countByStatus(TaskStatus status) {
		return tasks.countByStatus(status);
	}

	/**
	 * Escolhe o nó para a tarefa.
	 *
	 * <p>Filtro primeiro (vivo, fora da janela de remoção, perfil cobre o tipo, classe de dado pode
	 * ir para este nó, tem vaga, não está saturado, não esgotou a memória declarada), depois ordenação
	 * por saúde, origem, região e folga: nó degradado (lento ou errando recentemente) perde prioridade
	 * para todos os saudáveis; entre os saudáveis, o nó da infraestrutura do Énóis ({@code NODE_LOCAL})
	 * vem antes dos demais; dentro do mesmo grupo de origem, o nó da região do pedido vem antes de nós
	 * de outras regiões; e dentro do mesmo grupo de região quem tem mais vaga proporcional recebe.
	 * Empate é resolvido por jitter — sem ele, todos os nós empatados receberiam sempre na mesma ordem
	 * e o primeiro da lista viraria gargalo.</p>
	 *
	 * <p>A remoção temporária é filtro, não penalidade: um nó que falhou
	 * {@link NetworkNode#FAILURE_STREAK_LIMIT} vezes seguidas fica fora da lista até a janela vencer.
	 * Penalizá-lo só o colocaria no fim da fila — e com um único nó na rede ele continuaria
	 * recebendo trabalho, esgotando as tentativas de cada tarefa depois da última.</p>
	 *
	 * <p>{@code reserved} é quantas tarefas esta rodada já designou a cada nó. A folga e a vaga de um
	 * candidato são do último heartbeat; sem descontar o que a própria rodada entregou, a designação
	 * passa por cima do limite de concorrência que o participante declarou.</p>
	 */
	private Optional<NetworkNode> choose(NetworkTask task, List<NetworkNode> candidates, List<String> evacuated,
			Instant now, Map<String, Integer> reserved) {
		List<NetworkNode> eligible = candidates.stream()
			// Região evacuada é FILTRO, ao contrário da afinidade (ADR-012), e a diferença é o que
			// "evacuar" significa: afinidade é preferência entre nós que podem executar; evacuação é
			// a afirmação de que este nó NÃO pode executar agora. Designar para ele seria o loop que
			// a evacuação existe para romper — tarefa presa, claim vencido, devolvida, designada ao
			// mesmo nó da região caída de novo, até morrer de tentativas.
			.filter(node -> !NetworkRegions.outOfService(evacuated, node.region()))
			// Alvo vem antes de todo critério de mérito: é vínculo, não desempate. Filtrar depois do
			// jitter seria mandar ao nó errado um trabalho que o nó errado não sabe continuar.
			.filter(node -> task.targetNodeId() == null || node.id().equals(task.targetNodeId()))
			.filter(node -> node.alive(now, nodeStaleAfter))
			.filter(node -> !node.quarantined(now))
			.filter(node -> node.accepts(task.taskType(), task.dataClass()))
			.filter(node -> hasRoom(node, reserved))
			.filter(node -> !node.saturated())
			.filter(node -> !node.memoryExhausted())
			.sorted(Comparator.comparing(NetworkTaskService::degraded)
				.thenComparing(NetworkTaskService::localPriority)
				.thenComparing(Comparator.comparingInt(node -> regionPenalty(task.region(), node)))
				.thenComparing(Comparator.<NetworkNode>comparingDouble(node -> slack(node, reserved)).reversed())
				.thenComparingDouble(node -> ThreadLocalRandom.current().nextDouble()))
			.toList();
		return eligible.stream().findFirst();
	}

	/**
	 * O nó ainda tem vaga depois do que esta rodada já reservou. O nó que reporta em execução e o
	 * que já recebeu nesta rodada são o mesmo tipo de ocupação — contá-los juntos é o que mantém a
	 * designação dentro do limite declarado.
	 */
	private static boolean hasRoom(NetworkNode node, Map<String, Integer> reserved) {
		return node.runningTasks() + reserved.getOrDefault(node.id(), 0) < node.concurrencyLimit();
	}

	/** Nó degradado: lento medido, ou errando recentemente sem ter chegado ao limite da janela. */
	private static boolean degraded(NetworkNode node) {
		return node.slow() || node.failing();
	}

	/** Escapa o texto para caber em provedor JSON de auditoria, mesmo sendo id gerado por nós. */
	private static String escape(String text) {
		if (text == null) {
			return "";
		}
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/**
	 * Ordenação por origem: nó da própria infraestrutura do Énóis recebe antes dos demais.
	 *
	 * <p>É <b>prioridade de escalonamento, não permissão extra</b> (ADR-02). O perfil não abre tipo de
	 * tarefa nem classe de dado que outro nó não tenha, e não passa por cima de nenhum filtro: um nó
	 * local lento, errando ou sem vaga continua fora como qualquer outro. O que ele ganha é escolha
	 * primeiro entre os saudáveis — para que o pico seja absorvido na máquina da casa em vez de na
	 * máquina do participante.</p>
	 *
	 * <p>Fica <b>antes</b> da folga porque o critério de escalonamento é de origem, não de ocupação:
	 * aplicado depois, o local só seria preferido quando já fosse o mais folgado, e a prioridade não
	 * existiria justamente no momento em que ela importa (todos os nós com vaga).</p>
	 */
	private static boolean localPriority(NetworkNode node) {
		return !node.profiles().contains(NodeProfile.NODE_LOCAL);
	}

	/**
	 * Afinidade de região: 0 para o nó da região do pedido, 1 para os demais.
	 *
	 * <p>É <b>ordenação</b>, nunca filtro (ADR-012). Filtrar esvaziaria a fila sempre que a região do
	 * pedido não tivesse nó elegível — a tarefa esperaria a região voltar em vez de rodar no vizinho,
	 * e a rede cooperativa existe justamente para não ter esse ponto único. Sem região no pedido, ou
	 * sem região declarada no nó, todos empatam em 0 e o escalonamento é o de antes.</p>
	 *
	 * <p>Fica <b>depois</b> da origem ({@code NODE_LOCAL}): a casa continua absorvendo o pico antes
	 * de chegar ao participante, como em ADR-010. Quando há local com vaga, ele recebe; a região
	 * decide entre os demais. Antes da folga, porque afinidade aplicada depois só valeria no empate
	 * exato de vagas — e é no caso normal (todos com vaga parecida) que ela precisa existir.</p>
	 */
	private static int regionPenalty(String taskRegion, NetworkNode node) {
		if (taskRegion == null || node.region() == null) {
			return 0;
		}
		return taskRegion.equalsIgnoreCase(node.region()) ? 0 : 1;
	}

	/**
	 * Folga do nó: vagas livres sobre o limite, descontando o que esta rodada já entregou. Quem tem
	 * mais folga recebe a próxima tarefa — e quem recebeu nesta rodada vai perdendo a preferência,
	 * que é o que espalha a fila em vez de empilhar tudo no primeiro nó da ordem.
	 */
	private static double slack(NetworkNode node, Map<String, Integer> reserved) {
		int free = node.concurrencyLimit() - node.runningTasks() - reserved.getOrDefault(node.id(), 0);
		return (double) Math.max(0, free) / node.concurrencyLimit();
	}

	/**
	 * Registra o desfecho na saúde do nó e, se o streak chegou ao limite, tira o nó do
	 * balanceamento por uma janela.
	 *
	 * <p>Depois de gravar o streak, a linha é relida: o UPDATE é relativo (o banco soma), então o
	 * valor em memória não serve para decidir a janela — duas falhas simultâneas chegariam aqui com
	 * o mesmo streak antigo e as duas abririam janela. A releitura é uma consulta por desfecho, e
	 * desfecho é uma vez por tarefa, não por requisição de heartbeat.</p>
	 */
	private void recordOutcome(NetworkNode node, boolean success, Instant dispatchedAt, Instant now) {
		Long latency = success && dispatchedAt != null ? Math.max(0, Duration.between(dispatchedAt, now).toMillis())
			: null;
		nodes.recordOutcome(node.id(), success, latency);
		if (success) {
			return;
		}
		nodes.findById(node.id()).ifPresent(updated -> {
			if (!updated.failing() || updated.quarantined(now)) {
				return;
			}
			Instant until = now.plus(quarantineWindow);
			nodes.quarantine(updated.id(), "FAILURE_STREAK", until);
			audit.record("system", "NETWORK_NODE_QUARANTINED", updated.id(),
				"{\"reason\":\"FAILURE_STREAK\",\"failures\":" + updated.consecutiveFailures() + ",\"until\":\""
					+ until + "\"}");
			LOG.warn("No {} removido do balanceamento ate {} ({} falhas seguidas)", updated.id(), until,
				updated.consecutiveFailures());
		});
	}

	/**
	 * Backoff exponencial com jitter: 10s, 20s, 40s... até 5 min, mais até 20% de ruído. O jitter
	 * evita que todas as tarefas que falharam juntas voltem juntas.
	 */
	static Duration backoff(int attempt) {
		long base = Math.min(300L, 10L * (1L << Math.min(attempt, 5)));
		long jitter = (long) (base * 0.2 * ThreadLocalRandom.current().nextDouble());
		return Duration.ofSeconds(base + jitter);
	}

	private static void requireOperable(NetworkNode node) {
		if (!node.status().canOperate()) {
			throw new ApiException(403, "NODE_" + node.status().name(), "No nao esta ativo.");
		}
	}

	private static String truncate(String text) {
		return text.length() > MAX_ERROR ? text.substring(0, MAX_ERROR) : text;
	}

	/**
	 * Pedido de tarefa. {@code region} é opcional e a sobrecarga de cinco argumentos existe para não
	 * obrigar quem não tem preferência a dizer "sem preferência" — o valor antigo de quatro campos
	 * mais deadline produz exatamente o mesmo comportamento de antes desta rodada.
	 */
	/**
	 * @param targetNodeId nó a que a tarefa pertence; {@code null} = qualquer nó elegível. Não confundir
	 * com {@code region}: região ordena entre elegíveis, alvo restringe quem é elegível.
	 */
	public record EnqueueCommand(TaskType taskType, String idempotencyKey, String payload, DataClass dataClass,
			String region, Instant deadline, String targetNodeId) {

		/** Pedido sem preferência de região: o escalonamento é o mesmo de antes de a região existir. */
		public EnqueueCommand(TaskType taskType, String idempotencyKey, String payload, DataClass dataClass,
				Instant deadline) {
			this(taskType, idempotencyKey, payload, dataClass, null, deadline, null);
		}

		/** Pedido com alvo (trabalho que depende do estado local daquele nó). */
		public EnqueueCommand(TaskType taskType, String idempotencyKey, String payload, DataClass dataClass,
				String region, Instant deadline) {
			this(taskType, idempotencyKey, payload, dataClass, region, deadline, null);
		}
	}

	public record ResultCommand(String taskId, int attempt, boolean done, String output, String error,
			boolean retryable) {
	}

	/** O que aconteceu com o resultado: aceito ou descartado, e por quê. */
	public record ResultOutcome(String taskId, String status, boolean applied, String reason) {
	}
}
