package com.socialcoisa.rede.paporeto.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataPapoFalaJpaRepository extends JpaRepository<PapoFalaJpaEntity, String> {

	@Query("""
			select f from PapoFalaJpaEntity f
			where f.hora >= :de and f.hora < :ate
			order by f.hora asc
			""")
	List<PapoFalaJpaEntity> falasEntre(@Param("de") Instant de, @Param("ate") Instant ate, Pageable pageable);

	@Query("select max(f.lidaEm) from PapoFalaJpaEntity f")
	Optional<Instant> maiorLeitura();

	@Query("""
			select count(f) from PapoFalaJpaEntity f
			where f.hora >= :de and f.hora < :ate
			""")
	long falasTotais(@Param("de") Instant de, @Param("ate") Instant ate);

	/** As expressões que a publicação já falou (a deduplicação do corte usa isto). */
	@Query("""
			select f.expressao from PapoFalaJpaEntity f
			where f.publicacaoId = :publicacaoId
			""")
	List<String> expressoesDePublicacao(@Param("publicacaoId") String publicacaoId);

}

interface SpringDataPapoAssuntoJpaRepository extends JpaRepository<PapoAssuntoJpaEntity, PapoAssuntoJpaEntity.Chave> {

	List<PapoAssuntoJpaEntity> findByEscopo(String escopo);

	void deleteByEscopoAndSubjectIn(String escopo, List<String> subjects);

	@Modifying
	@Query("delete from PapoAssuntoJpaEntity a where a.escopo = :escopo")
	void deleteByEscopo(@Param("escopo") String escopo);

}

interface SpringDataPapoIntervaloJpaRepository extends JpaRepository<PapoIntervaloJpaEntity, String> {

	@Query("""
			select i from PapoIntervaloJpaEntity i
			where i.escopo = :escopo and i.subject = :subject and i.hora >= :de and i.hora < :ate
			order by i.hora asc
			""")
	List<PapoIntervaloJpaEntity> intervalosDe(@Param("escopo") String escopo, @Param("subject") String subject,
			@Param("de") Instant de, @Param("ate") Instant ate);

	@Modifying
	@Query("delete from PapoIntervaloJpaEntity i where i.escopo = :escopo")
	void deleteByEscopo(@Param("escopo") String escopo);

}
