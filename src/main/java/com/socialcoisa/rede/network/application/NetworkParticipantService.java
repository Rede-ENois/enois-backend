package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.feed.application.port.out.AuditLog;
import com.socialcoisa.rede.feed.application.port.out.AuditLogRepository;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.network.application.port.out.NetworkTaskRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.shared.web.ApiException;


/**
 * A visão do participante sobre os próprios nós — {@code /network/participant}, autenticado por
 * sessão, não por chave de nó (docs/rede-distribuida/, interface do participante).
 *
 * <p>A fronteira é {@code owner_user_id}: a consulta por nó devolve só o que é dele, e a leitura
 * isolada confere o dono antes de responder. O participante vê o que cede, o que a rede observou e
 * o que o nó já concluiu — nunca dados de outro participante, nunca a chave de API em claro (a
 * chave é emitida pelo admin e mostrada uma única vez a ele).</p>
 */
@Service
public class NetworkParticipantService {

	private static final int MAX_LIST = 200;

	/** Quantas reconexões recentes entram na soma da disponibilidade. */
	private static final int RECONNECT_SAMPLE = 200;

	/**
	 * A ação de auditoria em que o NÓ é o ator: o resultado que ele entregou foi recusado pela
	 * conferência e a tarefa voltou para a fila. É a única devolução que é ato dele — claim vencido
	 * é auditado como {@code system}, porque o nó não devolveu nada, ele sumiu no meio do trabalho.
	 */
	static final String RESULT_REFUSED = "NETWORK_TASK_RESULT_REFUSED";

	/**
	 * Ação em que o NÓ é o ator e o fato é a <b>volta</b> dele: esteve fora da janela de "vivo" e
	 * bateu coração de novo, com {@code offlineForSeconds} no metadado (V305/§8.6). É a prova do
	 * que o §18 chama de "consultar disponibilidade" — quantas quedas e por quanto tempo, somado.
	 */
	static final String NODE_RECONNECTED = "NETWORK_NODE_RECONNECTED";

	private final NetworkNodeRepository nodes;
	private final NetworkTaskRepository tasks;
	private final AuditLogRepository audit;
	private final NetworkNodeService lifecycle;
	private final Clock clock;
	private final long staleSeconds;

	NetworkParticipantService(NetworkNodeRepository nodes, NetworkTaskRepository tasks, AuditLogRepository audit,
			NetworkNodeService lifecycle, Clock clock,
			// Mesma janela de "vivo" do painel, do scheduler e da reconexão: um nó "online" aqui e
			// "fora" no painel seria o participante vendo dois números para a mesma coisa.
			@Value("${rede.network.node-stale-seconds:180}") long staleSeconds) {
		this.nodes = nodes;
		this.tasks = tasks;
		this.audit = audit;
		this.lifecycle = lifecycle;
		this.clock = clock;
		this.staleSeconds = staleSeconds;
	}

	@Transactional(readOnly = true)
	public List<NodeWithContribution> myNodes(String ownerUserId) {
		List<NetworkNode> found = nodes.listByOwner(ownerUserId, MAX_LIST);
		Map<String, Long> done = tasks.countDoneByNode(found.stream().map(NetworkNode::id).toList());
		return found.stream()
			.map(node -> new NodeWithContribution(node, done.getOrDefault(node.id(), 0L), requeued(node.id()),
				availability(node)))
			.toList();
	}

	@Transactional(readOnly = true)
	public NodeWithContribution myNode(String ownerUserId, String nodeId) {
		NetworkNode node = lifecycle.require(nodeId);
		if (!node.ownerUserId().equals(ownerUserId)) {
			// Nó de outro participante é 404, não 403: 403 confirmaria que o id existe.
			throw new ApiException(404, "NODE_UNKNOWN", "No nao encontrado.");
		}
		return new NodeWithContribution(node, tasks.countDoneByNode(List.of(nodeId)).getOrDefault(nodeId, 0L),
			requeued(nodeId), availability(node));
	}

	/**
	 * Desconexão voluntária. O participante não revoga sozinho — revogação é decisão administrativa
	 * —, mas pode tirar o servidor da rede: o nó vai para {@code SUSPENDED} (reversível pelo admin),
	 * as chaves vigentes caem na mesma transação como na revogação, e a ação é auditada com o ator
	 * sendo o próprio participante.
	 */
	@Transactional
	public NodeWithContribution disconnect(String ownerUserId, String nodeId) {
		return myNode(ownerUserId, lifecycle.disconnectVoluntarily(nodeId, ownerUserId).id());
	}

	/**
	 * Pausa a contribuição do nó: ele para de receber trabalho sem sair da rede. Diferente do
	 * {@link #disconnect}, a chave continua válida e o heartbeat continua sendo aceito — o
	 * participante pausa porque precisa da máquina agora, não porque quer sair.
	 */
	@Transactional
	public NodeWithContribution pause(String ownerUserId, String nodeId) {
		return myNode(ownerUserId, lifecycle.pauseParticipation(nodeId, ownerUserId).id());
	}

	/**
	 * O participante redefine o que cede no próprio nó (brief §18, "definir capacidade máxima" e
	 * "definir limites de banda"). Campo ausente mantém o valor atual.
	 */
	@Transactional
	public NodeWithContribution capacity(String ownerUserId, String nodeId, Integer cpuCores, Integer memoryMb,
			Integer diskMb, Integer bandwidthMbps) {
		return myNode(ownerUserId,
			lifecycle.participantCapacity(nodeId, ownerUserId, cpuCores, memoryMb, diskMb, bandwidthMbps).id());
	}

	/** Retoma a contribuição pausada pelo próprio participante. */
	@Transactional
	public NodeWithContribution resume(String ownerUserId, String nodeId) {
		return myNode(ownerUserId, lifecycle.resumeParticipation(nodeId, ownerUserId).id());
	}

	/** Solicitação de ingresso: o token nasce com o próprio participante como dono e criador. */
	@Transactional
	public NetworkNodeService.IssuedToken requestJoin(String ownerUserId, String displayName) {
		return lifecycle.requestJoin(ownerUserId, displayName);
	}

	/**
	 * A disponibilidade medida do nó. A soma e a última ausência vêm das linhas de reconexão, que
	 * guardam {@code offlineForSeconds} no metadado.
	 */
	private Availability availability(NetworkNode node) {
		List<AuditLog> rows = audit.findByTargetAndAction(node.id(), NODE_RECONNECTED, RECONNECT_SAMPLE);
		int quedas = (int) audit.countByActorAndAction(new UserId(node.id()), NODE_RECONNECTED);
		long soma = 0;
		int somadas = 0;
		Long ultima = null;
		for (AuditLog row : rows) {
			Long fora = offlineSeconds(row.metadata());
			if (fora == null) {
				// Linha ilegível continua CONTANDO como queda — ela prova que houve uma —, mas não
				// entra na soma: somar zero diria "voltou no mesmo instante", que é outra coisa.
				continue;
			}
			soma += fora;
			somadas++;
			if (ultima == null) {
				// A consulta vem ordenada do mais recente para o mais antigo: a primeira que se lê é
				// a última ausência.
				ultima = fora;
			}
		}
		boolean online = node.alive(clock.instant(), Duration.ofSeconds(staleSeconds));
		// `offlineSeconds` cobre `quedas` ausências, ou só as que couberam na amostra? A tela do
		// participante tem de dizer isso: uma soma que para no 200º registro e não avisa faria um
		// nó com histórico longo aparecer mais disponível do que foi.
		boolean completo = somadas == quedas;
		return new Availability(quedas, somadas, soma, ultima, online, completo);
	}

	/** Ler o {@code offlineForSeconds} do metadado — {@code null} quando a linha não tem o campo. */
	static Long offlineSeconds(String metadata) {
		if (metadata == null) {
			return null;
		}
		java.util.regex.Matcher match = java.util.regex.Pattern.compile("\"offlineForSeconds\"\\s*:\\s*(\\d+)")
			.matcher(metadata);
		return match.find() ? Long.valueOf(match.group(1)) : null;
	}

	/** Quantas tarefas este nó entregou e devolveu à fila, contadas na trilha que as prova. */
	private long requeued(String nodeId) {
		return audit.countByActorAndAction(new UserId(nodeId), RESULT_REFUSED);
	}

	/**
	 * O nó do participante com a contribuição medida — o número que a tela dele mostra.
	 *
	 * @param tasksDone     tarefas concluídas <b>por este nó como último executor</b>; uma tarefa
	 *                      que ele perdeu e outro concluiu não entra aqui
	 * @param tasksRequeued tarefas que ele <b>entregou</b> e tiveram o resultado recusado, voltando
	 *                      à fila. Somado a {@code tasksDone}, é o que distingue "não recebi trabalho"
	 *                      de "recebi e devolvi" — dois zeros do contador sozinhos dizem o mesmo
	 */
	public record NodeWithContribution(NetworkNode node, long tasksDone, long tasksRequeued, Availability availability) {
	}

	/**
	 * Disponibilidade medida: o que a rede sabe sobre o tempo em que este nó esteve no ar.
	 *
	 * <p>Não é um número único porque não existe um — a rede registra <b>transições</b>, não cada
	 * heartbeat (uma linha por ciclo por nó afogaria a trilha; ver {@code registrarReconexao}). O
	 * que dá para afirmar com a trilha é: quantas vezes ele voltou de uma ausência, e quanto tempo
	 * somou fora.</p>
	 *
	 * @param outages            quantas vezes o nó voltou depois de ficar fora da janela de "vivo"
	 * @param measuredOutages    quantas dessas {@code outages} entraram na soma (as que couberam na
	 *                           amostra e tinham o intervalo gravado)
	 * @param offlineSeconds     soma do tempo que ele passou fora, somando os intervalos medidos
	 * @param lastOfflineSeconds a última ausência conhecida — o caso recente que ele vai querer olhar
	 * @param online             se ele está dentro da janela agora (mesmo critério do painel)
	 * @param complete           {@code false} quando {@code offlineSeconds} soma menos ausências do que
	 *                           {@code outages}. O número deixa de ser o total e a tela precisa dizer
	 *                           que é um piso, não o tempo fora de verdade.
	 */
	public record Availability(int outages, int measuredOutages, long offlineSeconds, Long lastOfflineSeconds,
			boolean online, boolean complete) {
	}
}
