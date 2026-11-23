package com.socialcoisa.rede.confessionario.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.confessionario.domain.ConfissaoStatus;

interface SpringDataConfissaoPostJpaRepository extends JpaRepository<ConfissaoPostJpaEntity, String> {

	@Query("""
		select p from ConfissaoPostJpaEntity p
		where p.status = :status and p.createdAt < :before
		order by p.createdAt desc
		""")
	List<ConfissaoPostJpaEntity> feedBefore(@Param("status") ConfissaoStatus status, @Param("before") Instant before,
		Pageable pageable);

	@Query("""
		select p from ConfissaoPostJpaEntity p
		where p.status = :status and p.createdAt >= :de and p.createdAt < :ate
		order by p.createdAt asc, p.id asc
		""")
	List<ConfissaoPostJpaEntity> findAtivosEntre(@Param("status") ConfissaoStatus status,
		@Param("de") Instant de, @Param("ate") Instant ate, Pageable pageable);

	@Query("""
		select p from ConfissaoPostJpaEntity p
		where p.status = :status and p.createdAt < :ate
		  and (p.createdAt > :inicio or (p.createdAt = :inicio and p.id >= :depois))
		order by p.createdAt asc, p.id asc
		""")
	List<ConfissaoPostJpaEntity> findAtivosDaChave(@Param("status") ConfissaoStatus status,
		@Param("inicio") Instant inicio, @Param("depois") String depois, @Param("ate") Instant ate,
		Pageable pageable);
}
