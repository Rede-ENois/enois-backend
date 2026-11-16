package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.NodeKeyRepository;

@Repository
class NodeKeyPersistenceAdapter implements NodeKeyRepository {

	private final SpringDataNodeKeyJpaRepository repository;

	NodeKeyPersistenceAdapter(SpringDataNodeKeyJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public Optional<NodeKeyRecord> findActiveByNode(String nodeId) {
		return repository.findActiveByNode(nodeId).map(NodeKeyPersistenceAdapter::toRecord);
	}

	@Override
	public Map<String, NodeKeyRecord> findActiveByNodes(Collection<String> nodeIds) {
		if (nodeIds == null || nodeIds.isEmpty()) {
			return Map.of();
		}
		return repository.findActiveByNodes(nodeIds)
			.stream()
			.map(NodeKeyPersistenceAdapter::toRecord)
			.collect(Collectors.toMap(NodeKeyRecord::nodeId, Function.identity(), (first, second) -> first));
	}

	@Override
	public Optional<NodeKeyRecord> findByHash(String keyHash) {
		return repository.findByKeyHash(keyHash).map(NodeKeyPersistenceAdapter::toRecord);
	}

	@Override
	public void insert(String id, String nodeId, String keyHash, String keyPrefix, String createdBy, Instant now) {
		repository.save(new NodeKeyJpaEntity(id, nodeId, keyHash, keyPrefix, createdBy, now));
	}

	@Override
	public int revokeActiveByNode(String nodeId, String actorId, Instant now) {
		return repository.revokeActiveByNode(nodeId, actorId, now);
	}

	@Override
	public int revokeById(String keyId, String actorId, Instant now) {
		return repository.revokeById(keyId, actorId, now);
	}

	@Override
	public void touchLastUsed(String keyId, Instant now) {
		repository.touchLastUsed(keyId, now);
	}

	private static NodeKeyRecord toRecord(NodeKeyJpaEntity entity) {
		return new NodeKeyRecord(entity.id(), entity.nodeId(), entity.keyHash(), entity.keyPrefix(),
			entity.createdAt(), entity.lastUsedAt(), entity.revokedAt());
	}
}
