package com.socialcoisa.rede.network.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
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

	/**
	 * A ação de auditoria em que o NÓ é o ator: o resultado que ele entregou foi recusado pela
	 * conferência e a tarefa voltou para a fila. É a única devolução que é ato dele — claim vencido
	 * é auditado como {@code system}, porque o nó não devolveu nada, ele sumiu no meio do trabalho.
	 */
	static final String RESULT_REFUSED = "NETWORK_TASK_RESULT_REFUSED";

	private final NetworkNodeRepository nodes;
	private final NetworkTaskRepository tasks;
	private final AuditLogRepository audit;
	private final NetworkNodeService lifecycle;

	NetworkParticipantService(NetworkNodeRepository nodes, NetworkTaskRepository tasks, AuditLogRepository audit,
			NetworkNodeService lifecycle) {
		this.nodes = nodes;
		this.tasks = tasks;
		this.audit = audit;
		this.lifecycle = lifecycle;
	}

	@Transactional(readOnly = true)
	public List<NodeWithContribution> myNodes(String ownerUserId) {
		List<NetworkNode> found = nodes.listByOwner(ownerUserId, MAX_LIST);
		Map<String, Long> done = tasks.countDoneByNode(found.stream().map(NetworkNode::id).toList());
		return found.stream()
			.map(node -> new NodeWithContribution(node, done.getOrDefault(node.id(), 0L), requeued(node.id())))
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
			requeued(nodeId));
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

	/** Solicitação de ingresso: o token nasce com o próprio participante como dono e criador. */
	@Transactional
	public NetworkNodeService.IssuedToken requestJoin(String ownerUserId, String displayName) {
		return lifecycle.requestJoin(ownerUserId, displayName);
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
	public record NodeWithContribution(NetworkNode node, long tasksDone, long tasksRequeued) {
	}
}
