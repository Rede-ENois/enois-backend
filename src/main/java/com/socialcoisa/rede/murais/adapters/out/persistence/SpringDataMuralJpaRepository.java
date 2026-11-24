package com.socialcoisa.rede.murais.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.murais.domain.MuralStatus;

interface SpringDataMuralJpaRepository extends JpaRepository<MuralJpaEntity, String> {

	@Query("""
		select m from MuralJpaEntity m
		where m.status = :status and m.createdAt < :before
		order by m.createdAt desc
		""")
	List<MuralJpaEntity> ativosBefore(@Param("status") MuralStatus status, @Param("before") Instant before,
		Pageable pageable);

	/** Os ativos antes da marca, com o termo no título ou na descrição. Busca desligada é `""`. */
	@Query("""
		select m from MuralJpaEntity m
		where m.status = :status and m.createdAt < :before
		  and (:search = ''
		       or lower(m.title) like :pattern escape '\\' or lower(coalesce(m.description, '')) like :pattern escape '\\')
		order by m.createdAt desc
		""")
	List<MuralJpaEntity> ativosBeforeBuscando(@Param("status") MuralStatus status, @Param("before") Instant before,
		@Param("search") String search, @Param("pattern") String pattern, Pageable pageable);

	@Query("""
		select m from MuralJpaEntity m
		where m.status = com.socialcoisa.rede.murais.domain.MuralStatus.ACTIVE and m.ownerId = :ownerId
		order by m.createdAt desc
		""")
	List<MuralJpaEntity> ativosDoDono(@Param("ownerId") String ownerId);
}
