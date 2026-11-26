package com.socialcoisa.rede.portfolio.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataPortfolioPageJpaRepository extends JpaRepository<PortfolioPageJpaEntity, String> {

	/**
	 * O que já está no ar, mais recente primeiro. A chave de paginação é (data da publicação, dono):
	 * duas vitrines publicadas no mesmo segundo são desempatadas pelo id, que é único, e não por
	 * posição — publicar entre duas páginas não pula nem repete ninguém.
	 */
	@Query("""
		select p from PortfolioPageJpaEntity p
		where p.publishedVersion > 0
		  and (:before is null
			or p.publishedAt < :before
			or (p.publishedAt = :before and p.userId > :beforeId))
		order by p.publishedAt desc, p.userId desc
		""")
	List<PortfolioPageJpaEntity> findPublished(@Param("before") Instant before, @Param("beforeId") String beforeId,
			Pageable limit);
}