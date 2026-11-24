package com.socialcoisa.rede.talentbank.adapters.out.persistence;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.talentbank.application.port.out.TalentBankEventRepository;
import com.socialcoisa.rede.talentbank.domain.TalentBankEvent;

@Repository
class TalentBankEventPersistenceAdapter implements TalentBankEventRepository {

	private final SpringDataTalentBankEventJpaRepository repository;

	TalentBankEventPersistenceAdapter(SpringDataTalentBankEventJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public void save(TalentBankEvent event) {
		repository.save(TalentBankEventJpaEntity.fromDomain(event));
	}

	@Override
	public List<TalentBankEvent> findOfBank(String bankId) {
		return repository.findByBankIdOrderByCreatedAtDesc(bankId).stream()
			.map(TalentBankEventJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<TalentBankEvent> findOfEnrollment(String enrollmentId) {
		return repository.findByEnrollmentIdOrderByCreatedAtDesc(enrollmentId).stream()
			.map(TalentBankEventJpaEntity::toDomain)
			.toList();
	}
}
