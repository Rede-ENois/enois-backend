package com.socialcoisa.rede.shared.audit;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.feed.application.port.out.AuditLogRepository;
import com.socialcoisa.rede.feed.domain.UserId;

@Repository
class AuditLogPersistenceAdapter implements AuditLogRepository {

	private final SpringDataAuditLogJpaRepository repository;
	private final Clock clock;

	AuditLogPersistenceAdapter(SpringDataAuditLogJpaRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Override
	public void record(UserId actorId, String action, String targetId, String metadata) {
		repository.save(new AuditLogJpaEntity(UUID.randomUUID().toString(), actorId.value(), action, targetId, metadata,
			clock.instant()));
	}

	@Override
	public java.util.List<AuditRecord> recentForTarget(String targetId, int limit) {
		return repository.findByTargetIdOrderByCreatedAtDesc(targetId,
			org.springframework.data.domain.PageRequest.ofSize(limit))
			.stream()
			.map(entity -> new AuditRecord(entity.id(), entity.actorId(), entity.action(), entity.targetId(),
				entity.metadata(), entity.createdAt()))
			.toList();
	}

	@Override
	public long countByActorAndAction(UserId actorId, String action) {
		return repository.countByActorIdAndAction(actorId.value(), action);
	}

	@Override
	public List<String> allMetadataFor(String targetId, String action) {
		return repository.findMetadataByTargetIdAndAction(targetId, action);
	}

	@Override
	public List<com.socialcoisa.rede.feed.application.port.out.AuditLog> findByTargetAndAction(String targetId,
			String action, int limit) {
		return repository.findByTargetIdAndActionOrderByCreatedAtDesc(targetId, action, PageRequest.of(0, limit))
			.stream()
			.map(AuditLogJpaEntity::toRecord)
			.toList();
	}
}
