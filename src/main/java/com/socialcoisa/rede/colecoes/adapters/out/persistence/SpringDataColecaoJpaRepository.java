package com.socialcoisa.rede.colecoes.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataColecaoJpaRepository extends JpaRepository<ColecaoSalvaJpaEntity, String> {

	@Query("""
		select c from ColecaoSalvaJpaEntity c
		where c.ownerId = :ownerId and c.createdAt < :before
		order by c.createdAt desc
		""")
	List<ColecaoSalvaJpaEntity> doDonoBefore(@Param("ownerId") String ownerId, @Param("before") Instant before,
		Pageable pageable);

	/** Todas as coleções do dono, mais novas primeiro — o seletor do post lista tudo. */
	List<ColecaoSalvaJpaEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
