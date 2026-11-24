package com.socialcoisa.rede.colecoes.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataColecaoJpaRepository extends JpaRepository<ColecaoSalvaJpaEntity, String> {

	@Query("""
		select c from ColecaoSalvaJpaEntity c
		where c.ownerId = :ownerId and c.createdAt < :before
		order by c.createdAt desc
		""")
	List<ColecaoSalvaJpaEntity> doDonoBefore(@Param("ownerId") String ownerId, @Param("before") Instant before,
		Pageable pageable);

	/** As do dono antes da marca, com o termo no título ou na descrição. Busca desligada é `""`. */
	@Query("""
		select c from ColecaoSalvaJpaEntity c
		where c.ownerId = :ownerId and c.createdAt < :before
		  and (:search = ''
		       or lower(c.title) like :pattern escape '\\' or lower(coalesce(c.description, '')) like :pattern escape '\\')
		order by c.createdAt desc
		""")
	List<ColecaoSalvaJpaEntity> doDonoBeforeBuscando(@Param("ownerId") String ownerId, @Param("before") Instant before,
		@Param("search") String search, @Param("pattern") String pattern, Pageable pageable);

	/** Todas as coleções do dono, mais novas primeiro — o seletor do post lista tudo. */
	List<ColecaoSalvaJpaEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
