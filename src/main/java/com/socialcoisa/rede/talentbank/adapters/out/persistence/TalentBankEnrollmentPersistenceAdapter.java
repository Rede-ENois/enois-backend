package com.socialcoisa.rede.talentbank.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.talentbank.application.port.out.TalentBankEnrollmentRepository;
import com.socialcoisa.rede.talentbank.domain.EnrollmentState;
import com.socialcoisa.rede.talentbank.domain.TalentBankEnrollment;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

@Repository
class TalentBankEnrollmentPersistenceAdapter implements TalentBankEnrollmentRepository {

	private final EntityManager em;
	private final SpringDataTalentBankEnrollmentJpaRepository repository;

	TalentBankEnrollmentPersistenceAdapter(EntityManager em,
			SpringDataTalentBankEnrollmentJpaRepository repository) {
		this.em = em;
		this.repository = repository;
	}

	@Override
	public TalentBankEnrollment save(TalentBankEnrollment enrollment) {
		return repository.save(TalentBankEnrollmentJpaEntity.fromDomain(enrollment)).toDomain();
	}

	@Override
	public Optional<TalentBankEnrollment> findById(String id) {
		return repository.findById(id).map(TalentBankEnrollmentJpaEntity::toDomain);
	}

	@Override
	public Optional<TalentBankEnrollment> findByBankAndCandidate(String bankId, String candidateId) {
		return repository.findByBankIdAndCandidateId(bankId, candidateId)
			.map(TalentBankEnrollmentJpaEntity::toDomain);
	}

	@Override
	public List<TalentBankEnrollment> findByCandidate(String candidateId) {
		return repository.findByCandidateIdOrderByUpdatedAtDescIdDesc(candidateId).stream()
			.map(TalentBankEnrollmentJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<String> candidateIdsByState(String bankId, EnrollmentState state) {
		return repository.findByBankIdAndStateOrderByUpdatedAtDescIdDesc(bankId, state).stream()
			.map(TalentBankEnrollmentJpaEntity::getCandidateId)
			.toList();
	}

	@Override
	public List<String> candidateIdsOfBank(String bankId) {
		return repository.findByBankId(bankId).stream()
			.map(TalentBankEnrollmentJpaEntity::getCandidateId)
			.toList();
	}

	@Override
	public long countByBankAndState(String bankId, EnrollmentState state) {
		return repository.countByBankIdAndState(bankId, state);
	}

	@Override
	public List<TalentBankEnrollment> pageOfBank(String bankId, Collection<String> candidateIds, EnrollmentState state,
			Instant beforeUpdatedAt, String beforeId, int limit) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<TalentBankEnrollmentJpaEntity> criteria = cb.createQuery(TalentBankEnrollmentJpaEntity.class);
		Root<TalentBankEnrollmentJpaEntity> root = criteria.from(TalentBankEnrollmentJpaEntity.class);

		List<Predicate> filters = new ArrayList<>();
		filters.add(cb.equal(root.get("bankId"), bankId));
		if (candidateIds != null && !candidateIds.isEmpty()) {
			filters.add(root.get("candidateId").in(candidateIds));
		}
		if (state != null) {
			filters.add(cb.equal(root.get("state"), state));
		}
		if (beforeUpdatedAt != null) {
			filters.add(cb.or(
				cb.lessThan(root.get("updatedAt"), beforeUpdatedAt),
				cb.and(cb.equal(root.get("updatedAt"), beforeUpdatedAt), cb.lessThan(root.get("id"), beforeId))));
		}
		criteria.select(root)
			.where(cb.and(filters.toArray(Predicate[]::new)))
			.orderBy(cb.desc(root.get("updatedAt")), cb.desc(root.get("id")));

		return em.createQuery(criteria).setMaxResults(Math.max(1, limit)).getResultList().stream()
			.map(TalentBankEnrollmentJpaEntity::toDomain)
			.toList();
	}
}
