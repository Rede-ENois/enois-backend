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

	/**
	 * Redefine o que o participante cede (cpu/memória/disco/banda) e os perfis autorizados.
	 *
	 * <p>É mudança de permissão, não de estado: a config assinada do nó carrega limites e perfis,
	 * então a leitura seguinte só é aplicável se vier com versão nova — por isso a implementação
	 * soma 1 em {@code configVersion} e carimba {@code configIssuedAt}, como toda transição
	 * administrativa.</p>
	 */
	void updateContract(String id, int cpuCores, int memoryMb, int diskMb, int bandwidthMbps,
			List<NodeProfile> profiles, Instant now);

	/** Heartbeat: capacidade observada, versão do runtime e último contato. */
	void touch(String id, String runtimeVersion, Double cpuPercent, Integer memoryMb, Integer runningTasks,
			Instant now);

	long countByStatus(NodeStatus status);

	/**
	 * Nós que podem receber tarefa agora: ACTIVE, com heartbeat recente e com vaga. A filtragem por
	 * perfil e classe de dado é do domínio — o banco não sabe o que é {@code NODE_MEDIA}.
	 */
	List<NetworkNode> findCandidates(Instant seenAfter, int limit);

	/**
	 * Grava o desfecho de uma tarefa no histórico de saúde do nó.
	 *
	 * <p>O streak de falha zera no sucesso e o de sucesso zera na falha: o que caracteriza nó doente
	 * é erro <b>seguido</b>, e um nó que acertou agora não pode carregar erro velho. A latência
	 * também entra aqui — é o instante da conclusão, e quem tem o instante é este fluxo.</p>
	 */
	void recordOutcome(String id, boolean success, Long latencyMs);

	/** Tira o nó do balanceamento até {@code until}. */
	void quarantine(String id, String reason, Instant until);

	/** Devolve o nó ao balanceamento: limpa a janela e o streak de falha. */
	void release(String id);

	/** Nós cuja janela de remoção venceu, mais antigos primeiro, para a varredura de volta. */
	List<NetworkNode> findQuarantineExpired(Instant now, int limit);

	/**
	 * A versão e o instante da permissão vigente do nó — o que a config assinada devolve ao runtime.
	 *
	 * <p>Não é {@code status} disfarçado: é o carimbo que o nó compara para saber que a permissão
	 * mudou. Fica fora do agregado {@link NetworkNode} porque nenhuma decisão de despacho olha para
	 * ele; só a leitura da config.</p>
	 */
	Optional<ConfigStamp> configStamp(String id);

	/** Versão monotônica da permissão e o instante em que ela passou a valer. */
	record ConfigStamp(int version, Instant issuedAt) {
	}
}
