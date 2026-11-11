package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.jobs.application.port.out.ExternalJobApplicationRepository;
import com.socialcoisa.rede.jobs.domain.ExternalJobApplication;

@Repository
class ExternalJobApplicationPersistenceAdapter implements ExternalJobApplicationRepository {

	private final SpringDataExternalJobApplicationJpaRepository repository;

	ExternalJobApplicationPersistenceAdapter(SpringDataExternalJobApplicationJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public void save(ExternalJobApplication application) {
		repository.save(ExternalJobApplicationJpaEntity.fromDomain(application));
	}

	@Override
	public Optional<ExternalJobApplication> findByJobAndUser(String externalJobId, String userId) {
		return repository.findByExternalJobIdAndUserId(externalJobId, userId)
			.map(ExternalJobApplicationJpaEntity::toDomain);
	}

	@Override
	public List<ExternalJobApplication> findByUser(String userId, int limit) {
		return repository.findByUserIdOrderByAppliedAtDesc(userId, PageRequest.of(0, Math.max(1, limit))).stream()
			.map(ExternalJobApplicationJpaEntity::toDomain)
			.toList();
	}
}
