package com.socialcoisa.rede.network.domain;

import java.time.Instant;
import java.util.Set;

/**
 * Um nó da rede cooperativa: um servidor autorizado que executa trabalho do Énóis.
 *
 * <p>A identidade é do nó, não do dono — o dono não herda permissão nenhuma. Permissão é dado do
 * Control Plane (esta tabela), e o nó só lê a própria configuração.</p>
 *
 * @param id identificador estável do nó (nd_…)
 * @param displayName nome legível para o painel
 * @param ownerUserId conta responsável pelo participante (auditoria e contato)
 * @param status estado atual; REVOKED é terminal
 * @param profiles perfis autorizados; vazio = nenhum tipo de tarefa
 * @param region região declarada pelo participante
 * @param runtimeVersion versão do runtime reportada no último heartbeat
 * @param lastSeenAt último heartbeat; null = nunca conectou
 */
public record NetworkNode(String id, String displayName, String ownerUserId, NodeStatus status,
		Set<NodeProfile> profiles, String region, String runtimeVersion, Instant lastSeenAt) {

	public NetworkNode {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("Node id vazio.");
		}
		if (displayName == null || displayName.isBlank()) {
			throw new IllegalArgumentException("Node displayName vazio.");
		}
		if (ownerUserId == null || ownerUserId.isBlank()) {
			throw new IllegalArgumentException("Node ownerUserId vazio.");
		}
		if (status == null) {
			throw new IllegalArgumentException("Node status nulo.");
		}
		profiles = profiles == null ? Set.of() : Set.copyOf(profiles);
	}

	public boolean canOperate() {
		return status.canOperate() && !profiles.isEmpty();
	}
}
