package com.socialcoisa.rede.shared.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataProcessedEventJpaRepository extends JpaRepository<ProcessedEventJpaEntity, String> {
}
