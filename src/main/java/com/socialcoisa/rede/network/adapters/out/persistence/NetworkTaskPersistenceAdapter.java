package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.NetworkTaskRepository;
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.TaskStatus;
import com.socialcoisa.rede.network.domain.TaskType;

@Repository
class NetworkTaskPersistenceAdapter implements NetworkTaskRepository {

	private final SpringDataNetworkTaskJpaRepository repository;

	NetworkTaskPersistenceAdapter(SpringDataNetworkTaskJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public Optional<NetworkTask> findById(String id) {
		return repository.findById(id).map(NetworkTaskJpaEntity::toDomain);
	}

	@Override
	public Optional<NetworkTask> findByIdempotencyKey(String idempotencyKey) {
		return repository.findByIdempotencyKey(idempotencyKey).map(NetworkTaskJpaEntity::toDomain);
	}

	@Override
	public void create(String id, TaskType type, String idempotencyKey, String payload, DataClass dataClass,
			Instant deadline, Instant nextAttemptAt, Instant now) {
		repository.save(new NetworkTaskJpaEntity(id, type.name(), idempotencyKey, payload, dataClass.name(), deadline,
			nextAttemptAt, now));
	}

	@Override
	public List<NetworkTask> findDispatchable(Instant now, int limit) {
		return repository.findDispatchable(now, PageRequest.ofSize(limit))
			.stream()
			.map(NetworkTaskJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<NetworkTask> findAbandoned(Instant now, int limit) {
		return repository.findAbandoned(now, PageRequest.ofSize(limit))
			.stream()
			.map(NetworkTaskJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<NetworkTask> findAssignedTo(String nodeId, Instant now, int limit) {
		return repository.findAssignedTo(nodeId, now, PageRequest.ofSize(limit))
			.stream()
			.map(NetworkTaskJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<NetworkTask> findOnNonOperatingNodes(Instant now, int limit) {
		return repository.findOnNonOperatingNodes(now, PageRequest.ofSize(limit))
			.stream()
			.map(NetworkTaskJpaEntity::toDomain)
			.toList();
	}

	@Override
	public long countOnNonOperatingNodes(Instant now) {
		return repository.countOnNonOperatingNodes(now);
	}

	@Override
	public int claim(String id, String nodeId, int expectedAttempt, Instant claimExpiresAt, Instant now) {
		return repository.claim(id, nodeId, expectedAttempt, claimExpiresAt, now);
	}

	@Override
	public int complete(String id, int attempt, String output, Instant now) {
		return repository.complete(id, attempt, output, now);
	}

	@Override
	public int fail(String id, int attempt, String error, Instant now) {
		return repository.fail(id, attempt, error, now);
	}

	@Override
	public int requeue(String id, int attempt, String error, Instant nextAttemptAt, Instant now) {
		return repository.requeue(id, attempt, error, nextAttemptAt, now);
	}

	@Override
	public int kill(String id, int attempt, String error, Instant now) {
		return repository.kill(id, attempt, error, now);
	}

	@Override
	public int cancel(String id, String actorId, Instant now) {
		return repository.cancel(id, now);
	}

	@Override
	public long countByStatus(TaskStatus status) {
		return repository.countByStatus(status.name());
	}

	@Override
	public List<NetworkTask> listRecent(int limit) {
		return repository.listRecent(PageRequest.ofSize(limit))
			.stream()
			.map(NetworkTaskJpaEntity::toDomain)
			.toList();
	}
}
