package com.socialcoisa.rede.shared.outbox;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.feed.application.port.out.OutboxEventRepository;

@Repository
class OutboxEventPersistenceAdapter implements OutboxEventRepository {

	private final SpringDataOutboxEventJpaRepository repository;
	private final Clock clock;

	OutboxEventPersistenceAdapter(SpringDataOutboxEventJpaRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Override
	public void append(String eventType, String aggregateId, String payload) {
		repository.save(new OutboxEventJpaEntity(UUID.randomUUID().toString(), eventType, aggregateId, payload,
			clock.instant()));
	}
}
