package com.socialcoisa.rede.murais.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataMuralContribuicaoJpaRepository extends JpaRepository<MuralContribuicaoJpaEntity, String> {

	@Query("""
		select c from MuralContribuicaoJpaEntity c
		where c.muralId = :muralId and c.createdAt < :before
		order by c.createdAt desc
		""")
	List<MuralContribuicaoJpaEntity> doMuralBefore(@Param("muralId") String muralId,
		@Param("before") Instant before, Pageable pageable);

	@Query("""
		select c.muralId, count(c) from MuralContribuicaoJpaEntity c
		where c.muralId in :ids
		group by c.muralId
		""")
	List<Object[]> countByMuralIdIn(@Param("ids") Collection<String> ids);
}
