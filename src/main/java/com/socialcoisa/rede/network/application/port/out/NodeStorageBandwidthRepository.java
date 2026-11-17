package com.socialcoisa.rede.network.application.port.out;

import java.util.Map;

import com.socialcoisa.rede.network.domain.StorageNodeKind;

/**
 * A banda própria de cada tipo de storage node (brief §6, V347).
 *
 * <p>Ausência de linha é "não especializado": o tipo usa o {@code declaredBandwidthMbps} do nó.
 * Nunca zero, que seria banda nenhuma — a mesma regra de nulo ≠ zero que o módulo aplica em toda
 * medida.</p>
 */
public interface NodeStorageBandwidthRepository {

	/** Os limites por tipo declarados para este nó. Vazio quando ele não especializou nenhum. */
	Map<StorageNodeKind, Integer> findByNode(String nodeId);

	/** Define (ou substitui) o limite de um tipo. */
	void put(String nodeId, StorageNodeKind kind, int bandwidthMbps);

	/** Remove o limite do tipo: ele volta a usar a banda geral do nó. */
	void remove(String nodeId, StorageNodeKind kind);
}
