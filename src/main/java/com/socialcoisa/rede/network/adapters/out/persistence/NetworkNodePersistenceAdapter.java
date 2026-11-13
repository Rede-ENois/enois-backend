package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;

@Repository
class NetworkNodePersistenceAdapter implements NetworkNodeRepository {

	private final SpringDataNetworkNodeJpaRepository repository;

	NetworkNodePersistenceAdapter(SpringDataNetworkNodeJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public Optional<NetworkNode> findById(String id) {
		return repository.findById(id).map(NetworkNodeJpaEntity::toDomain);
	}

	@Override
	public List<NetworkNode> list(int limit) {
		return repository.listRecent(PageRequest.ofSize(limit))
			.stream()
			.map(NetworkNodeJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<NetworkNode> listByOwner(String ownerUserId, int limit) {
		return repository.listByOwner(ownerUserId, PageRequest.ofSize(limit))
			.stream()
			.map(NetworkNodeJpaEntity::toDomain)
			.toList();
	}

	@Override
	public void create(String id, String displayName, String ownerUserId, List<NodeProfile> profiles, String region,
			String country, int cpuCores, int memoryMb, int diskMb, int bandwidthMbps, Instant now) {
		repository.save(new NetworkNodeJpaEntity(id, displayName, ownerUserId, NodeStatus.PENDING_APPROVAL.name(),
			NetworkNodeJpaEntity.NodeProfilesCsv.join(profiles), cpuCores, memoryMb, diskMb, bandwidthMbps, region,
			country, now));
	}

	@Override
	public void updateStatus(String id, NodeStatus status, String actorId, Instant now) {
		if (status == NodeStatus.ACTIVE) {
			repository.approve(id, status.name(), actorId, now);
			return;
		}
		if (status == NodeStatus.REVOKED) {
			repository.revoke(id, status.name(), now);
			return;
		}
		repository.changeStatus(id, status.name(), now);
	}

	@Override
	public void updateContract(String id, int cpuCores, int memoryMb, int diskMb, int bandwidthMbps,
			List<NodeProfile> profiles, Instant now) {
		repository.updateContract(id, cpuCores, memoryMb, diskMb, bandwidthMbps,
			NetworkNodeJpaEntity.NodeProfilesCsv.join(profiles), now);
	}

	@Override
	public void touch(String id, String runtimeVersion, Double cpuPercent, Integer memoryMb, Integer runningTasks,
			Instant now) {
		repository.heartbeat(id, runtimeVersion, cpuPercent, memoryMb, runningTasks, now);
	}

	@Override
	public long countByStatus(NodeStatus status) {
		return repository.countByStatus(status.name());
	}

	@Override
	public List<NetworkNode> findCandidates(Instant seenAfter, int limit) {
		return repository.findCandidates(seenAfter, PageRequest.ofSize(limit))
			.stream()
			.map(NetworkNodeJpaEntity::toDomain)
			.toList();
	}

	@Override
	public void recordOutcome(String id, boolean success, Long latencyMs) {
		repository.recordOutcome(id, success, latencyMs);
	}

	@Override
	public void quarantine(String id, String reason, Instant until) {
		repository.quarantine(id, reason, until);
	}

	@Override
	public void release(String id) {
		repository.release(id);
	}

	@Override
	public Optional<NetworkNodeRepository.ConfigStamp> configStamp(String id) {
		// Linha anterior à V284 pode estar sem o carimbo: o instante em que o nó passou a existir de
		// fato é a melhor resposta que não inventa data — a permissão vale desde que ele entrou.
		return repository.findById(id)
			.map(entity -> new NetworkNodeRepository.ConfigStamp(entity.configVersion(),
				entity.configIssuedAt() != null ? entity.configIssuedAt() : entity.createdAt()));
	}

	@Override
	public List<NetworkNode> findQuarantineExpired(Instant now, int limit) {
		return repository.findQuarantineExpired(now, PageRequest.ofSize(limit))
			.stream()
			.map(NetworkNodeJpaEntity::toDomain)
			.toList();
	}
}
