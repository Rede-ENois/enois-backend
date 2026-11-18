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

	/** Elos de UM post nas coleções indicadas — o suficiente para a flag "tem" do seletor. */
	@Query("""
		select e.colecaoId, e.postId from ColecaoPostJpaEntity e
		where e.postId = :postId and e.colecaoId in :colecaoIds
		""")
	List<Object[]> peloPostNasColecoes(@Param("postId") String postId,
		@Param("colecaoIds") Collection<String> colecaoIds);

	void deleteByColecaoId(String colecaoId);
}
