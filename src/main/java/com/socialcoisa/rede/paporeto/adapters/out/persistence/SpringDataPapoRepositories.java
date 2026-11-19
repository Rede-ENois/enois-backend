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

	/**
	 * As publicações que falaram a expressão no escopo e na janela, da mais recente para a mais
	 * antiga — uma linha por publicação (GROUP BY), porque a mesma publicação pode ter falado o
	 * assunto em mais de uma expressão. O escopo vazio é o global (todas as comunidades).
	 */
	@Query("""
			select f.publicacaoId as publicacaoId, f.fonte as fonte, f.communityId as communityId,
				max(f.hora) as hora
			from PapoFalaJpaEntity f
			where f.expressao = :expressao and f.hora >= :de and f.hora < :ate
				and (:escopo = '' or f.communityId = :escopo)
			group by f.publicacaoId, f.fonte, f.communityId
			order by max(f.hora) desc
			""")
	List<PapoPublicacaoProjecao> publicacoesDoAssunto(@Param("escopo") String escopo,
			@Param("expressao") String expressao, @Param("de") Instant de, @Param("ate") Instant ate,
			Pageable pageable);

}

/** A linha crua da visão de publicações relacionadas: identidade e origem, sem texto. */
interface PapoPublicacaoProjecao {

	String getPublicacaoId();

	String getFonte();

	String getCommunityId();

	Instant getHora();

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
