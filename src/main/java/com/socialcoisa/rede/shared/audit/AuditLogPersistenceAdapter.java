package com.socialcoisa.rede.shared.audit;

import java.time.Clock;
import java.util.UUID;

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
}
