package com.socialcoisa.rede.support.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.support.application.port.out.SupportMessageRepository;
import com.socialcoisa.rede.support.domain.SupportMessage;

@Repository
class SupportMessagePersistenceAdapter implements SupportMessageRepository {

	private final SpringDataSupportMessageJpaRepository repository;

	SupportMessagePersistenceAdapter(SpringDataSupportMessageJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public List<SupportMessage> findByRequestId(String requestId) {
		return repository.findByRequestIdOrderByCreatedAtAsc(requestId).stream()
			.map(SupportMessageJpaEntity::toDomain).toList();
	}

	@Override
	public Optional<SupportMessage> findByGithubCommentId(long commentId) {
		return repository.findByGithubCommentId(commentId).map(SupportMessageJpaEntity::toDomain);
	}

	@Override
	public Optional<SupportMessage> findById(String id) {
		return repository.findById(id).map(SupportMessageJpaEntity::toDomain);
	}

	@Override
	public SupportMessage save(SupportMessage message) {
		return repository.save(SupportMessageJpaEntity.fromDomain(message)).toDomain();
	}

	@Override
	public void deleteById(String id) {
		repository.deleteById(id);
		repository.flush();
	}
}