package com.socialcoisa.rede.shared.outbox;

import java.time.Clock;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class OutboxWorker {

	private final SpringDataOutboxEventJpaRepository outboxRepository;
	private final SpringDataProcessedEventJpaRepository processedEventRepository;
	private final Clock clock;

	OutboxWorker(SpringDataOutboxEventJpaRepository outboxRepository,
			SpringDataProcessedEventJpaRepository processedEventRepository, Clock clock) {
		this.outboxRepository = outboxRepository;
		this.processedEventRepository = processedEventRepository;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${rede.outbox.worker-delay-ms:5000}")
	@Transactional
	void processPendingEvents() {
		List<OutboxEventJpaEntity> events = outboxRepository
			.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("PENDING", clock.instant());
		for (OutboxEventJpaEntity event : events) {
			if (!processedEventRepository.existsById(event.id())) {
				processedEventRepository.save(new ProcessedEventJpaEntity(event.id(), clock.instant()));
			}
			event.markProcessed();
			outboxRepository.save(event);
		}
	}
}
