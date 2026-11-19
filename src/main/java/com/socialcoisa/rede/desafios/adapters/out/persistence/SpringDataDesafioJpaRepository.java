package com.socialcoisa.rede.desafios.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.desafios.domain.DesafioStatus;

interface SpringDataDesafioJpaRepository extends JpaRepository<DesafioJpaEntity, String> {

	@Query("""
		select d from DesafioJpaEntity d
		where d.status <> :removido and d.createdAt < :before
		order by d.createdAt desc
		""")
	List<DesafioJpaEntity> feedBefore(@Param("removido") DesafioStatus removido, @Param("before") Instant before,
		Pageable pageable);

	@Query("""
		select d from DesafioJpaEntity d
		where d.authorId = :authorId and d.status = :status
		order by d.createdAt desc
		""")
	List<DesafioJpaEntity> ativosDoAutor(@Param("authorId") String authorId,
		@Param("status") DesafioStatus status, Pageable pageable);
}
