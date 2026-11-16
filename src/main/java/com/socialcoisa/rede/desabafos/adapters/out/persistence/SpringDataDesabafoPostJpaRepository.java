package com.socialcoisa.rede.desabafos.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.desabafos.domain.DesabafoStatus;

interface SpringDataDesabafoPostJpaRepository extends JpaRepository<DesabafoPostJpaEntity, String> {

	@Query("""
		select p from DesabafoPostJpaEntity p
		where p.status = :status and p.createdAt < :before
		order by p.createdAt desc
		""")
	List<DesabafoPostJpaEntity> feedBefore(@Param("status") DesabafoStatus status, @Param("before") Instant before,
		Pageable pageable);
}
