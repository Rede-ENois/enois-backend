package com.socialcoisa.rede.shared.outbox;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataOutboxEventJpaRepository extends JpaRepository<OutboxEventJpaEntity, String> {

	List<OutboxEventJpaEntity> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(String status,
			Instant nextAttemptAt);
}
