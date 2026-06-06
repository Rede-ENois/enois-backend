package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataJobJpaRepository extends JpaRepository<JobJpaEntity, String> {

	List<JobJpaEntity> findAllByOrderByPostedAtDesc();

	@Query("SELECT j FROM JobJpaEntity j WHERE j.type = :type ORDER BY j.postedAt DESC")
	List<JobJpaEntity> findByTypeOrderByPostedAtDesc(@Param("type") String type);

	@Query("SELECT j FROM JobJpaEntity j WHERE LOWER(j.title) LIKE LOWER(CONCAT('%', :query, '%')) "
			+ "OR LOWER(j.company) LIKE LOWER(CONCAT('%', :query, '%')) ORDER BY j.postedAt DESC")
	List<JobJpaEntity> searchByQuery(@Param("query") String query);

}
