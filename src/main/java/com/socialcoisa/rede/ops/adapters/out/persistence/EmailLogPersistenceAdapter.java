package com.socialcoisa.rede.ops.adapters.out.persistence;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.ops.application.port.out.EmailLogRepository;
import com.socialcoisa.rede.ops.domain.EmailLog;

@Repository
class EmailLogPersistenceAdapter implements EmailLogRepository {

	private final SpringDataEmailLogJpaRepository repository;

	EmailLogPersistenceAdapter(SpringDataEmailLogJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public EmailLog save(EmailLog log) {
		return repository.save(EmailLogJpaEntity.fromDomain(log)).toDomain();
	}

	@Override
	public int deleteOlderThan(java.time.Instant cutoff, int batchSize) {
		List<String> ids = repository.findIdsByCreatedAtBefore(cutoff, PageRequest.ofSize(batchSize));
		if (ids.isEmpty()) {
			return 0;
		}
		repository.deleteByIdIn(ids);
		return ids.size();
	}

	@Override
	public long countOlderThan(java.time.Instant cutoff) {
		return repository.countByCreatedAtBefore(cutoff);
	}

	@Override
	public List<EmailLog> findRecent(int limit) {
		return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
			.stream()
			.map(EmailLogJpaEntity::toDomain)
			.toList();
	}
}
