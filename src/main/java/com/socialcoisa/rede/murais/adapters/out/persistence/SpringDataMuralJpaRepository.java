package com.socialcoisa.rede.murais.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.murais.domain.MuralStatus;

interface SpringDataMuralJpaRepository extends JpaRepository<MuralJpaEntity, String> {

	@Query("""
		select m from MuralJpaEntity m
		where m.status = :status and m.createdAt < :before
		order by m.createdAt desc
		""")
	List<MuralJpaEntity> ativosBefore(@Param("status") MuralStatus status, @Param("before") Instant before,
		Pageable pageable);
}
