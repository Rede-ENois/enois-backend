package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface SpringDataJobJpaRepository extends JpaRepository<JobJpaEntity, String> {

	// A listagem com filtros (inclusive a busca booleana) e montada com Criteria em
	// JobPersistenceAdapter: texto, nivel, tipo, local e habilidades precisam ser combinados na mesma
	// consulta, o que nao cabia no encadeamento de queries fixas que existia aqui (cada `else if`
	// descartava os outros filtros).

	@Query("SELECT j FROM JobJpaEntity j WHERE j.postedBy = :postedBy AND (:before IS NULL OR j.postedAt < :before) ORDER BY j.postedAt DESC")
	List<JobJpaEntity> findByPostedByAndPostedAtBeforeOrderByPostedAtDesc(@Param("postedBy") String postedBy,
			@Param("before") Instant before, Pageable pageable);

	@Query("SELECT j FROM JobJpaEntity j WHERE j.id IN :ids")
	List<JobJpaEntity> findByIdIn(@Param("ids") java.util.Collection<String> ids);

	// Diretorio de empresas: vagas abertas agrupadas por empresa (sem Case para agrupar
	// maiusculas/minusculas juntas — a variante mais frequente define o nome exibido, resolvido
	// no adapter). MAX(logo) pega um logo qualquer nao nulo do grupo.
	@Query("SELECT j.company, COUNT(j) AS total, MAX(j.logo) FROM JobJpaEntity j " +
		"WHERE j.status = com.socialcoisa.rede.jobs.domain.JobStatus.OPEN AND j.company IS NOT NULL " +
		"GROUP BY j.company ORDER BY COUNT(j) DESC")
	List<Object[]> countOpenJobsByCompany();

	@Query("SELECT j.company, COUNT(j) AS total, MAX(j.logo) FROM JobJpaEntity j " +
		"WHERE j.status = com.socialcoisa.rede.jobs.domain.JobStatus.OPEN AND j.company IS NOT NULL " +
		"AND j.companyId IS NULL GROUP BY j.company")
	List<Object[]> countOpenUnlinkedJobsByCompany();

	@Query("SELECT j.companyId, COUNT(j) FROM JobJpaEntity j " +
		"WHERE j.status = com.socialcoisa.rede.jobs.domain.JobStatus.OPEN AND j.companyId IN :ids GROUP BY j.companyId")
	List<Object[]> countOpenByCompanyIds(@Param("ids") java.util.Collection<String> ids);

	@Query("SELECT j FROM JobJpaEntity j WHERE j.companyId = :companyId " +
		"AND j.status = com.socialcoisa.rede.jobs.domain.JobStatus.OPEN ORDER BY j.postedAt DESC")
	List<JobJpaEntity> findOpenByCompanyId(@Param("companyId") String companyId, Pageable pageable);

	/**
	 * Le a vaga travando a linha ate o fim da transacao. E o ponto de serializacao entre
	 * candidatar-se e encerrar: quem pega o lock primeiro decide, e o outro le o estado ja mudado —
	 * sem isso uma candidatura podia ler OPEN, o encerramento commitar no meio e ela gravar depois.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT j FROM JobJpaEntity j WHERE j.id = :id")
	Optional<JobJpaEntity> findByIdForUpdate(@Param("id") String id);

}