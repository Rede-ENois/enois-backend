package com.socialcoisa.rede.doacoes.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.doacoes.application.port.out.DoacaoRepository;
import com.socialcoisa.rede.doacoes.domain.Doacao;
import com.socialcoisa.rede.doacoes.domain.DoacaoStatus;

@Repository
class DoacaoPersistenceAdapter implements DoacaoRepository {

	private final SpringDataDoacaoRepository repository;

	DoacaoPersistenceAdapter(SpringDataDoacaoRepository repository) {
		this.repository = repository;
	}

	@Override
	public Doacao save(Doacao doacao) {
		return repository.save(DoacaoJpaEntity.from(doacao)).toDomain();
	}

	@Override
	public Optional<Doacao> findById(String id) {
		return repository.findById(id).map(DoacaoJpaEntity::toDomain);
	}

	@Override
	public Optional<Doacao> findOpenByUser(String userId) {
		return repository.findByUserIdAndStatus(userId, DoacaoStatus.ABERTA).map(DoacaoJpaEntity::toDomain);
	}

	@Override
	public List<Doacao> byUser(String userId) {
		return repository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(DoacaoJpaEntity::toDomain).toList();
	}

	@Override
	public List<Doacao> byStatus(DoacaoStatus status, int offset, int limit) {
		PageRequest page = PageRequest.of(Math.max(0, offset) / Math.max(1, limit), Math.max(1, limit));
		return repository.findByStatusOrderByCreatedAtAsc(status, page).stream()
			.map(DoacaoJpaEntity::toDomain).toList();
	}

	@Override
	public long countByStatus(DoacaoStatus status) {
		return repository.countByStatus(status);
	}

	@Override
	public boolean anyPixIdUsed(Collection<String> pixIds) {
		return pixIds != null && !pixIds.isEmpty() && repository.countUsingPixIds(pixIds) > 0;
	}

	@Override
	public Set<String> confirmedUserIds(Collection<String> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return Set.of();
		}
		return repository.confirmedUserIds(userIds);
	}
}
