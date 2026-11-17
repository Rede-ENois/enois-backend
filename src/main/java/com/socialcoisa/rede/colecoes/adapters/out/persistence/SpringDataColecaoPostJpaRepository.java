package com.socialcoisa.rede.colecoes.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataColecaoPostJpaRepository extends JpaRepository<ColecaoPostJpaEntity, String> {

	@Query("""
		select e from ColecaoPostJpaEntity e
		where e.colecaoId = :colecaoId and e.createdAt < :before
		order by e.createdAt desc
		""")
	List<ColecaoPostJpaEntity> daColecaoBefore(@Param("colecaoId") String colecaoId, @Param("before") Instant before,
		Pageable pageable);

	@Query("""
		select e.colecaoId, count(e) from ColecaoPostJpaEntity e
		where e.colecaoId in :ids
		group by e.colecaoId
		""")
	List<Object[]> countByColecaoIdIn(@Param("ids") Collection<String> ids);

	void deleteByColecaoId(String colecaoId);
}
