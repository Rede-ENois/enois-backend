package com.socialcoisa.rede.confessionario.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.confessionario.domain.ConfissaoStatus;

interface SpringDataConfissaoPostJpaRepository extends JpaRepository<ConfissaoPostJpaEntity, String> {

	@Query("""
		select p from ConfissaoPostJpaEntity p
		where p.status = :status and p.createdAt < :before
		order by p.createdAt desc
		""")
	List<ConfissaoPostJpaEntity> feedBefore(@Param("status") ConfissaoStatus status, @Param("before") Instant before,
		Pageable pageable);
}
