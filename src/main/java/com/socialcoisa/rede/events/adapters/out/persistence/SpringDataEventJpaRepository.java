package com.socialcoisa.rede.events.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataEventJpaRepository extends JpaRepository<EventJpaEntity, String> {

	@Query("SELECT e FROM EventJpaEntity e WHERE (:before IS NULL OR e.createdAt < :before) ORDER BY e.createdAt DESC")
	List<EventJpaEntity> findAllBefore(@Param("before") Instant before, Pageable pageable);

	@Query("SELECT e FROM EventJpaEntity e WHERE e.format = :format AND (:before IS NULL OR e.createdAt < :before) ORDER BY e.createdAt DESC")
	List<EventJpaEntity> findByFormat(@Param("format") String format, @Param("before") Instant before,
			Pageable pageable);

}
