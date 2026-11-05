package com.socialcoisa.rede.companies.adapters.out.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataCompanyFollowJpaRepository
		extends JpaRepository<CompanyFollowJpaEntity, CompanyFollowJpaEntity.Key> {

	long countByCompanyId(String companyId);

	@Query("SELECT f.companyId, COUNT(f) FROM CompanyFollowJpaEntity f WHERE f.companyId IN :ids GROUP BY f.companyId")
	List<Object[]> countByCompanyIds(@Param("ids") Collection<String> ids);

	@Query("SELECT f.userId FROM CompanyFollowJpaEntity f WHERE f.companyId = :companyId")
	List<String> followerIds(@Param("companyId") String companyId);
}
