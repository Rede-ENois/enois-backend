package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.jobs.application.port.out.TalentPoolRepository;
import com.socialcoisa.rede.jobs.domain.TalentPoolOptIn;

@Repository
class TalentPoolPersistenceAdapter implements TalentPoolRepository {

	private final SpringDataTalentPoolJpaRepository repository;

	TalentPoolPersistenceAdapter(SpringDataTalentPoolJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public TalentPoolOptIn save(TalentPoolOptIn optIn) {
		return repository.save(TalentPoolJpaEntity.fromDomain(optIn)).toDomain();
	}

	@Override
	public Optional<TalentPoolOptIn> findByCandidateAndCompany(String candidateId, String companyId) {
		return repository.findByCandidateIdAndCompanyId(candidateId, companyId).map(TalentPoolJpaEntity::toDomain);
	}

	@Override
	public List<TalentPoolOptIn> activeOfCandidate(String candidateId) {
		return repository.findByCandidateIdAndWithdrawingAtIsNullOrderByConsentedAtDesc(candidateId).stream()
			.map(TalentPoolJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<TalentPoolOptIn> page(String companyId, Instant beforeConsentedAt, String beforeId, int limit) {
		return repository.pageOfCompany(companyId, beforeConsentedAt, beforeId, PageRequest.of(0, Math.max(1, limit)))
			.stream()
			.map(TalentPoolJpaEntity::toDomain)
			.toList();
	}

	@Override
	public int countActive(String companyId) {
		return (int) repository.countByCompanyIdAndWithdrawingAtIsNull(companyId);
	}

	@Override
	@Transactional
	public void purgeCandidate(String candidateId) {
		repository.deleteByCandidateId(candidateId);
	}

	@Override
	@Transactional
	public int purgeWithdrawnBefore(Instant before) {
		return repository.deleteWithdrawnBefore(before);
	}
}
