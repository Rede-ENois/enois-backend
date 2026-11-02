package com.socialcoisa.rede.companies.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMicroProjectReviewJpaRepository extends JpaRepository<MicroProjectReviewJpaEntity, String> {

	List<MicroProjectReviewJpaEntity> findTop6ByCompanyIdOrderByCreatedAtDesc(String companyId);
}
