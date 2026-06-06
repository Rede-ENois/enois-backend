package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataJobJpaRepository extends JpaRepository<JobJpaEntity, String> {

	@Query("SELECT j FROM JobJpaEntity j WHERE (:before IS NULL OR j.postedAt < :before) ORDER BY j.postedAt DESC")
	List<JobJpaEntity> findAllBefore(@Param("before") Instant before, Pageable pageable);

	@Query("SELECT j FROM JobJpaEntity j WHERE j.type = :type AND (:before IS NULL OR j.postedAt < :before) ORDER BY j.postedAt DESC")
	List<JobJpaEntity> findByType(@Param("type") String type, @Param("before") Instant before, Pageable pageable);

	@Query("SELECT j FROM JobJpaEntity j WHERE (LOWER(j.title) LIKE LOWER(CONCAT('%', :query, '%')) "
			+ "OR LOWER(j.company) LIKE LOWER(CONCAT('%', :query, '%'))) "
			+ "AND (:before IS NULL OR j.postedAt < :before) ORDER BY j.postedAt DESC")
	List<JobJpaEntity> searchByQuery(@Param("query") String query, @Param("before") Instant before, Pageable pageable);

	@Query("SELECT j FROM JobJpaEntity j WHERE j.level = :level AND (:before IS NULL OR j.postedAt < :before) ORDER BY j.postedAt DESC")
	List<JobJpaEntity> findByLevel(@Param("level") String level, @Param("before") Instant before, Pageable pageable);

	@Query("SELECT j FROM JobJpaEntity j WHERE j.location = :location AND (:before IS NULL OR j.postedAt < :before) ORDER BY j.postedAt DESC")
	List<JobJpaEntity> findByLocation(@Param("location") String location, @Param("before") Instant before,
			Pageable pageable);

	@Query("SELECT DISTINCT j FROM JobJpaEntity j JOIN j.skills s WHERE s IN :skills "
			+ "AND (:before IS NULL OR j.postedAt < :before) ORDER BY j.postedAt DESC")
	List<JobJpaEntity> findBySkills(@Param("skills") List<String> skills, @Param("before") Instant before,
			Pageable pageable);

}
