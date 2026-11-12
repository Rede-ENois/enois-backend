package com.socialcoisa.rede.network.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
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

	private final NetworkNodeRepository nodes;
	private final NetworkTaskRepository tasks;
	private final NetworkNodeService lifecycle;

	NetworkParticipantService(NetworkNodeRepository nodes, NetworkTaskRepository tasks,
			NetworkNodeService lifecycle) {
		this.nodes = nodes;
		this.tasks = tasks;
		this.lifecycle = lifecycle;
	}

	@Transactional(readOnly = true)
	public List<NodeWithContribution> myNodes(String ownerUserId) {
		List<NetworkNode> found = nodes.listByOwner(ownerUserId, MAX_LIST);
		Map<String, Long> done = tasks.countDoneByNode(found.stream().map(NetworkNode::id).toList());
		return found.stream().map(node -> new NodeWithContribution(node, done.getOrDefault(node.id(), 0L))).toList();
	}

	@Transactional(readOnly = true)
	public NodeWithContribution myNode(String ownerUserId, String nodeId) {
		NetworkNode node = lifecycle.require(nodeId);
		if (!node.ownerUserId().equals(ownerUserId)) {
			// Nó de outro participante é 404, não 403: 403 confirmaria que o id existe.
			throw new ApiException(404, "NODE_UNKNOWN", "No nao encontrado.");
		}
		return new NodeWithContribution(node, tasks.countDoneByNode(List.of(nodeId)).getOrDefault(nodeId, 0L));
	}

	/**
	 * Desconexão voluntária. O participante não revoga sozinho — revogação é decisão administrativa
	 * —, mas pode tirar o servidor da rede: o nó vai para {@code SUSPENDED} (reversível pelo admin),
	 * as chaves vigentes caem na mesma transação como na revogação, e a ação é auditada com o ator
	 * sendo o próprio participante.
	 */
	@Transactional
	public NetworkNode disconnect(String ownerUserId, String nodeId) {
		return lifecycle.disconnectVoluntarily(nodeId, ownerUserId);
	}

	/** Solicitação de ingresso: o token nasce com o próprio participante como dono e criador. */
	@Transactional
	public NetworkNodeService.IssuedToken requestJoin(String ownerUserId, String displayName) {
		return lifecycle.requestJoin(ownerUserId, displayName);
	}

	/** O nó do participante com a contribuição medida — o número que a tela dele mostra. */
	public record NodeWithContribution(NetworkNode node, long tasksDone) {
	}
}
