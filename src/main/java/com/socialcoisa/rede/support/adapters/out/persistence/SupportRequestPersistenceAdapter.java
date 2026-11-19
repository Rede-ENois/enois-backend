package com.socialcoisa.rede.support.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.support.application.port.out.SupportRequestRepository;
import com.socialcoisa.rede.support.domain.SupportCategory;
import com.socialcoisa.rede.support.domain.SupportRequest;
import com.socialcoisa.rede.support.domain.SupportStatus;

@Repository
class SupportRequestPersistenceAdapter implements SupportRequestRepository {

	private final SpringDataSupportRequestJpaRepository repository;

	SupportRequestPersistenceAdapter(SpringDataSupportRequestJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public SupportRequest save(SupportRequest request) {
		return repository.save(SupportRequestJpaEntity.fromDomain(request)).toDomain();
	}

	@Override
	public Optional<SupportRequest> findById(String id) {
		return repository.findById(id).map(SupportRequestJpaEntity::toDomain);
	}

	@Override
	public Optional<SupportRequest> findByIdForUpdate(String id) {
		return repository.findByIdForUpdate(id).map(SupportRequestJpaEntity::toDomain);
	}

	@Override
	public List<SupportRequest> findFiltered(SupportCategory category, SupportStatus status, int limit) {
		return repository.findFiltered(category, status, PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
			.stream()
			.map(SupportRequestJpaEntity::toDomain)
			.toList();
	}

	@Override
	public long countByUserSince(String userId, Instant since) {
		return repository.countByUserIdAndCreatedAtAfter(userId, since);
	}

	@Override
	public Optional<SupportRequest> findByGithubIssueNumber(int githubIssueNumber) {
		return repository.findByGithubIssueNumber(githubIssueNumber).map(SupportRequestJpaEntity::toDomain);
	}

	@Override
	public List<SupportRequest> findByUserId(String userId, int limit) {
		return repository.findByUserIdOrderByCreatedAtDesc(userId,
				PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
			.stream()
			.map(SupportRequestJpaEntity::toDomain)
			.toList();
	}
}
