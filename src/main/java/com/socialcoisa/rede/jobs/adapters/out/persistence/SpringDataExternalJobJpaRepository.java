package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataExternalJobJpaRepository extends JpaRepository<ExternalJobJpaEntity, String> {

	Optional<ExternalJobJpaEntity> findByPlatformAndExternalId(String platform, String externalId);

	Optional<ExternalJobJpaEntity> findFirstByCanonicalUrl(String canonicalUrl);

	List<ExternalJobJpaEntity> findAllByOrderByLastCheckedAtDesc(Pageable pageable);

	/** A listagem geral das externas EXCLUI a plataforma das cadastradas no Énóis. */
	List<ExternalJobJpaEntity> findByPlatformNotOrderByLastCheckedAtDesc(String platform, Pageable pageable);

	@Query("""
			SELECT e FROM ExternalJobJpaEntity e
			WHERE e.platform <> :platform
			  AND (LOWER(e.title) LIKE :pattern ESCAPE '!'
			   OR LOWER(e.company) LIKE :pattern ESCAPE '!'
			   OR LOWER(e.location) LIKE :pattern ESCAPE '!')
			ORDER BY e.lastCheckedAt DESC""")
	List<ExternalJobJpaEntity> searchExcludingPlatform(@Param("platform") String platform,
		@Param("pattern") String pattern, Pageable pageable);

	List<ExternalJobJpaEntity> findByPlatformOrderByLastCheckedAtDesc(String platform, Pageable pageable);

	@Query("SELECT e FROM ExternalJobJpaEntity e WHERE e.platform = :platform "
		+ "AND LOWER(e.title) LIKE :pattern ESCAPE '!' ORDER BY e.lastCheckedAt DESC")
	List<ExternalJobJpaEntity> searchByPlatform(@Param("platform") String platform,
		@Param("pattern") String pattern, Pageable pageable);

	@Query("SELECT DISTINCT e.platform FROM ExternalJobJpaEntity e ORDER BY e.platform")
	List<String> distinctPlatforms();

	List<ExternalJobJpaEntity> findByIdIn(java.util.Collection<String> ids);

	/** Vagas cadastradas pela propria pessoa (as importadas nao tem autor). */
	List<ExternalJobJpaEntity> findBySubmittedByOrderByImportedAtDesc(String submittedBy);

	// Mesmo agregado para as vagas externas (Remotive/Jobicy/...): empresa e total (a origem
	// nao guarda logo — o diretorio mostra a inicial quando nao ha logo de vaga interna).
	@Query("SELECT e.company, COUNT(e) AS total FROM ExternalJobJpaEntity e " +
		"WHERE e.company IS NOT NULL GROUP BY e.company ORDER BY COUNT(e) DESC")
	List<Object[]> countJobsByCompany();

	/**
	 * Busca das externas: titulo, EMPRESA ou local — a mesma regua da vaga interna.
	 *
	 * Antes so o titulo casava, e o diretorio de empresas (que e feito de empresas das vagas
	 * externas) levava a uma lista vazia: clicar em "Monzo — 8 vagas" buscava "Monzo", que e a
	 * empresa, e nao achava nada. `escape` e `lower` para o curinga do usuario nao virar busca
	 * ampla e a comparacao nao depender do collation.
	 */
	@Query("""
			SELECT e FROM ExternalJobJpaEntity e
			WHERE LOWER(e.title) LIKE :pattern ESCAPE '!'
			   OR LOWER(e.company) LIKE :pattern ESCAPE '!'
			   OR LOWER(e.location) LIKE :pattern ESCAPE '!'
			ORDER BY e.lastCheckedAt DESC""")
	List<ExternalJobJpaEntity> search(@org.springframework.data.repository.query.Param("pattern") String pattern,
			Pageable pageable);

	/**
	 * Vagas de uma fonte que não apareceram no último ciclo dela (`lastSeenAt` antes do corte) e
	 * que ainda não foram marcadas como removidas — candidatas a expiração.
	 */
	@Query("""
			SELECT e FROM ExternalJobJpaEntity e
			WHERE e.platform = :platform
			  AND e.lastSeenAt IS NOT NULL AND e.lastSeenAt < :seenBefore
			  AND e.removedAt IS NULL""")
	List<ExternalJobJpaEntity> findStaleNotRemoved(@Param("platform") String platform,
			@Param("seenBefore") java.time.Instant seenBefore, Pageable pageable);
}
