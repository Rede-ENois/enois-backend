package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;

/** Persistência dos nós da rede. Nada aqui é acessível a um nó externo. */
public interface NetworkNodeRepository {

	Optional<NetworkNode> findById(String id);

	List<NetworkNode> list(int limit);

	/** Cria o nó em PENDING_APPROVAL. */
	void create(String id, String displayName, String ownerUserId, List<NodeProfile> profiles, String region,
			String country, int cpuCores, int memoryMb, int diskMb, int bandwidthMbps, Instant now);

	void updateStatus(String id, NodeStatus status, String actorId, Instant now);

	/** Heartbeat: capacidade observada, versão do runtime e último contato. */
	void touch(String id, String runtimeVersion, Double cpuPercent, Integer memoryMb, Integer runningTasks,
			Instant now);

	long countByStatus(NodeStatus status);

	/**
	 * Nós que podem receber tarefa agora: ACTIVE, com heartbeat recente e com vaga. A filtragem por
	 * perfil e classe de dado é do domínio — o banco não sabe o que é {@code NODE_MEDIA}.
	 */
	List<NetworkNode> findCandidates(Instant seenAfter, int limit);
}
