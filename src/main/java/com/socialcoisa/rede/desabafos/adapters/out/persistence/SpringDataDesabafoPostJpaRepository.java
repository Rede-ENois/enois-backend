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

	@Query("""
		select p from DesabafoPostJpaEntity p
		where p.status = :status and p.createdAt >= :de and p.createdAt < :ate
		order by p.createdAt asc, p.id asc
		""")
	List<DesabafoPostJpaEntity> findAtivosEntre(@Param("status") DesabafoStatus status, @Param("de") Instant de,
		@Param("ate") Instant ate, Pageable pageable);

	/**
	 * A mesma janela, por chave (a leitura do Termômetro): de `inicio` (inclusivo) em diante
	 * quando `depois` está vazio — o começo da janela — e, no instante do cursor, só os ids a
	 * partir de `depois` (a página seguinte).
	 */
	@Query("""
		select p from DesabafoPostJpaEntity p
		where p.status = :status and p.createdAt < :ate
		  and (p.createdAt > :inicio or (p.createdAt = :inicio and p.id >= :depois))
		order by p.createdAt asc, p.id asc
		""")
	List<DesabafoPostJpaEntity> findAtivosDepois(@Param("status") DesabafoStatus status,
		@Param("inicio") Instant inicio, @Param("depois") String depois, @Param("ate") Instant ate,
		Pageable pageable);
}
