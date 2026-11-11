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
		repository.changeStatus(id, status.name());
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
}
