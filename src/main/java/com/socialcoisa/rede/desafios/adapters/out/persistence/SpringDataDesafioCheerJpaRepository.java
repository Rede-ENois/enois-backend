package com.socialcoisa.rede.desafios.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataDesafioCheerJpaRepository extends JpaRepository<DesafioCheerJpaEntity, String> {

	long deleteByParticipacaoIdAndUserId(String participacaoId, String userId);

	List<DesafioCheerJpaEntity> findByUserIdAndParticipacaoIdIn(String userId, Set<String> participacaoIds);

	@Query("""
		select c.participacaoId as participacaoId, count(c.id) as total
		from DesafioCheerJpaEntity c
		where c.participacaoId in :participacaoIds
		group by c.participacaoId
		""")
	List<CheerCountProjection> countByParticipacaoIdIn(@Param("participacaoIds") Collection<String> participacaoIds);

	interface CheerCountProjection {

		String getParticipacaoId();

		long getTotal();
	}
}
