package com.socialcoisa.rede.companies.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataRecruitmentMemberJpaRepository extends JpaRepository<RecruitmentMemberJpaEntity, String> {

	Optional<RecruitmentMemberJpaEntity> findByCompanyIdAndUserId(String companyId, String userId);

	List<RecruitmentMemberJpaEntity> findByCompanyIdOrderByCreatedAtAsc(String companyId);
}
