package com.socialcoisa.rede.suggestions.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.suggestions.application.port.out.SuggestionRepository;
import com.socialcoisa.rede.suggestions.domain.Suggestion;
import com.socialcoisa.rede.suggestions.domain.SuggestionStatus;

@Repository
class SuggestionPersistenceAdapter implements SuggestionRepository {

	private final SpringDataSuggestionJpaRepository repository;

	SuggestionPersistenceAdapter(SpringDataSuggestionJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	@Transactional
	public Suggestion save(Suggestion suggestion) {
		return repository.save(SuggestionJpaEntity.fromDomain(suggestion)).toDomain();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Suggestion> findById(String id) {
		return repository.findById(id).map(SuggestionJpaEntity::toDomain);
	}

	@Override
	@Transactional(readOnly = true)
	public List<Suggestion> findFiltered(SuggestionStatus status, int limit) {
		return repository.findFiltered(status, PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
			.stream()
			.map(SuggestionJpaEntity::toDomain)
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<Suggestion> findByUserId(String userId, int limit) {
		return repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
			.stream()
			.map(SuggestionJpaEntity::toDomain)
			.toList();
	}

	@Override
	@Transactional
	public boolean insertWithinLimit(Suggestion suggestion, Instant since, int max) {
		repository.lockUser(suggestion.userId());
		if (repository.countByUserIdAndCreatedAtAfter(suggestion.userId(), since) >= max) {
			return false;
		}
		repository.save(SuggestionJpaEntity.fromDomain(suggestion));
		return true;
	}

	@Override
	@Transactional
	public boolean transition(String id, SuggestionStatus from, SuggestionStatus to, String reviewedBy, Instant at) {
		return repository.transition(id, from, to, reviewedBy, at) == 1;
	}

	@Override
	@Transactional
	public boolean claimForPublishing(String id, String reviewedBy, Instant at) {
		return repository.claimForPublishing(id, reviewedBy, at) == 1;
	}
}
