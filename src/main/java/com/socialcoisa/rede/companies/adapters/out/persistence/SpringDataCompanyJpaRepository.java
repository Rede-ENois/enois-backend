package com.socialcoisa.rede.companies.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataCompanyJpaRepository extends JpaRepository<CompanyJpaEntity, String> {

	Optional<CompanyJpaEntity> findBySlug(String slug);

	boolean existsBySlug(String slug);

	@Query("""
			SELECT c FROM CompanyJpaEntity c
			WHERE c.id IN (SELECT m.companyId FROM CompanyMemberJpaEntity m WHERE m.userId = :userId)
			ORDER BY c.createdAt DESC""")
	List<CompanyJpaEntity> findByMember(@Param("userId") String userId, Pageable pageable);

	List<CompanyJpaEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

	/** Directory: active pages by name (`needle` already lowercased and wrapped in %) and sector. */
	@Query("""
			SELECT c FROM CompanyJpaEntity c
			WHERE c.status = com.socialcoisa.rede.companies.domain.CompanyStatus.ACTIVE
			AND (:needle IS NULL OR LOWER(c.name) LIKE :needle)
			AND (:sector IS NULL OR LOWER(c.sector) = :sector)
			ORDER BY c.createdAt DESC""")
	List<CompanyJpaEntity> findActive(@Param("needle") String needle, @Param("sector") String sector,
			Pageable pageable);

	@Query("SELECT c.name FROM CompanyJpaEntity c WHERE c.status = com.socialcoisa.rede.companies.domain.CompanyStatus.ACTIVE")
	List<String> activeNames();

	@Query("""
			SELECT DISTINCT c.sector FROM CompanyJpaEntity c
			WHERE c.status = com.socialcoisa.rede.companies.domain.CompanyStatus.ACTIVE
			AND c.sector IS NOT NULL AND c.sector <> ''""")
	List<String> activeSectors();
}
