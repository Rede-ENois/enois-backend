package com.socialcoisa.rede.talentbank.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataTalentBankEnrollmentJpaRepository extends JpaRepository<TalentBankEnrollmentJpaEntity, String> {

	Optional<TalentBankEnrollmentJpaEntity> findByBankIdAndCandidateId(String bankId, String candidateId);

	List<TalentBankEnrollmentJpaEntity> findByCandidateIdOrderByUpdatedAtDescIdDesc(String candidateId);

	List<TalentBankEnrollmentJpaEntity> findByBankIdAndStateOrderByUpdatedAtDescIdDesc(String bankId,
			com.socialcoisa.rede.talentbank.domain.EnrollmentState state);

	List<TalentBankEnrollmentJpaEntity> findByBankId(String bankId);

	long countByBankIdAndState(String bankId, com.socialcoisa.rede.talentbank.domain.EnrollmentState state);
}
