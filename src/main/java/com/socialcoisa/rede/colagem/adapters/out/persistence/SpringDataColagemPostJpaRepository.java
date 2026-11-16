package com.socialcoisa.rede.colagem.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.colagem.domain.ColagemStatus;

interface SpringDataColagemPostJpaRepository extends JpaRepository<ColagemPostJpaEntity, String> {

	List<ColagemPostJpaEntity> findByStatusOrderByCreatedAtDesc(ColagemStatus status, Pageable pageable);

	@Query("""
		select p from ColagemPostJpaEntity p
		where p.status = :status and p.createdAt < :before
		order by p.createdAt desc
		""")
	List<ColagemPostJpaEntity> feedBefore(@Param("status") ColagemStatus status, @Param("before") Instant before,
		Pageable pageable);

	List<ColagemPostJpaEntity> findByMediaIdAndStatus(String mediaId, ColagemStatus status);
}
