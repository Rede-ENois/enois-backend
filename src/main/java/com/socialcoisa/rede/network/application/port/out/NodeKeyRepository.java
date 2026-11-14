package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Chaves de API dos nós. A chave em claro nunca é persistida: só o hash SHA-256 e um prefixo curto
 * para diagnóstico humano.
 */
public interface NodeKeyRepository {

	/** Chave vigente (não revogada) do nó, se houver. */
	Optional<NodeKeyRecord> findActiveByNode(String nodeId);

	/**
	 * Chaves vigentes de vários nós numa consulta só. O painel lista até 200 nós; buscar a chave de
	 * cada um em separado seria N+1 numa tela que abre a cada ação administrativa.
	 */
	Map<String, NodeKeyRecord> findActiveByNodes(Collection<String> nodeIds);

	/** Busca pelo hash, para autenticar uma requisição de nó. */
	Optional<NodeKeyRecord> findByHash(String keyHash);

	void insert(String id, String nodeId, String keyHash, String keyPrefix, String createdBy, Instant now);

	/** Revoga a chave vigente do nó; devolve quantas linhas mudaram (0 = não havia chave ativa). */
	int revokeActiveByNode(String nodeId, String actorId, Instant now);

	int revokeById(String keyId, String actorId, Instant now);

	void touchLastUsed(String keyId, Instant now);

	record NodeKeyRecord(String id, String nodeId, String keyHash, String keyPrefix, Instant createdAt,
			Instant lastUsedAt, Instant revokedAt) {

		public boolean active() {
			return revokedAt == null;
		}
	}
}
