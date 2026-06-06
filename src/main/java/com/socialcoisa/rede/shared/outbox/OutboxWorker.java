package com.socialcoisa.rede.shared.outbox;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class OutboxWorker {

	private static final Logger LOG = LoggerFactory.getLogger(OutboxWorker.class);

	private final SpringDataOutboxEventJpaRepository outboxRepository;
	private final SpringDataProcessedEventJpaRepository processedEventRepository;
	private final List<OutboxEventHandler> handlers;
	private final Clock clock;

	OutboxWorker(SpringDataOutboxEventJpaRepository outboxRepository,
			SpringDataProcessedEventJpaRepository processedEventRepository,
			List<OutboxEventHandler> handlers, Clock clock) {
		this.outboxRepository = outboxRepository;
		this.processedEventRepository = processedEventRepository;
		this.handlers = handlers;
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
			for (OutboxEventHandler handler : handlers) {
				try {
					handler.handle(event.eventType(), event.aggregateId(), event.payload());
				} catch (Exception e) {
					LOG.warn("Handler {} falhou para evento {}: {}", handler.getClass().getSimpleName(),
						event.id(), e.getMessage());
				}
			}
			event.markProcessed();
			outboxRepository.save(event);
		}
	}
}
