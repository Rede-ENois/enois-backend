package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.NodeStorageBandwidthRepository;
import com.socialcoisa.rede.network.domain.StorageNodeKind;

/**
 * A banda por tipo de storage node (V348).
 *
 * <p>Duas instruções por operação, sem entidade JPA: a tabela é pequena, o par (nó, tipo) é a
 * chave, e carregar objetos gerenciados só para ler dois números daria contexto de persistência
 * para uma leitura de config assinada que não escreve nada.</p>
 */
@Repository
class NodeStorageBandwidthPersistenceAdapter implements NodeStorageBandwidthRepository {

	private final JdbcTemplate jdbc;

	NodeStorageBandwidthPersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<StorageNodeKind, Integer> findByNode(String nodeId) {
		Map<StorageNodeKind, Integer> byKind = new LinkedHashMap<>();
		jdbc.query("select kind, bandwidth_mbps from network_node_storage_bandwidth where node_id = ? order by kind",
			rs -> {
				// Tipo desconhecido no banco (versão futura que voltou a rodar com código antigo) é
				// ignorado em vez de derrubar a leitura da config: o nó continua recebendo a dele.
				try {
					byKind.put(StorageNodeKind.valueOf(rs.getString("kind")), rs.getInt("bandwidth_mbps"));
				}
				catch (IllegalArgumentException unknown) {
					// nada: o tipo não existe nesta versão
				}
			}, nodeId);
		return byKind;
	}

	@Override
	public void put(String nodeId, StorageNodeKind kind, int bandwidthMbps) {
		jdbc.update("insert into network_node_storage_bandwidth (node_id, kind, bandwidth_mbps, updated_at) "
			+ "values (?, ?, ?, ?) on duplicate key update bandwidth_mbps = values(bandwidth_mbps), "
			+ "updated_at = values(updated_at)", nodeId, kind.name(), bandwidthMbps, Instant.now());
	}

	@Override
	public void remove(String nodeId, StorageNodeKind kind) {
		jdbc.update("delete from network_node_storage_bandwidth where node_id = ? and kind = ?", nodeId,
			kind.name());
	}
}
