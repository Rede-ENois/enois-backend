package com.socialcoisa.rede.talentbank.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataTalentBankEventJpaRepository extends JpaRepository<TalentBankEventJpaEntity, String> {

	List<TalentBankEventJpaEntity> findByBankIdOrderByCreatedAtDesc(String bankId);

	List<TalentBankEventJpaEntity> findByEnrollmentIdOrderByCreatedAtDesc(String enrollmentId);
}
