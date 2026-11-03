package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataUserMuteJpaRepository extends JpaRepository<UserMuteJpaEntity, Long> {

	Optional<UserMuteJpaEntity> findByMuterIdAndMutedId(String muterId, String mutedId);

	List<UserMuteJpaEntity> findByMuterIdOrderByCreatedAtDesc(String muterId);

	/**
	 * So os silenciamentos que ainda valem: `expires_at` nulo e "ate reativar"; com prazo, conta
	 * apenas enquanto nao venceu. O instante vem do `Clock` da aplicacao, nunca de `now()` do banco:
	 * o mesmo comportamento em qualquer fuso e testavel com relogio fixo.
	 */
	@Query("""
			select m from UserMuteJpaEntity m
			where m.muterId = :muterId and m.mutedId = :mutedId
			  and (m.expiresAt is null or m.expiresAt > :now)""")
	Optional<UserMuteJpaEntity> findActive(@Param("muterId") String muterId, @Param("mutedId") String mutedId,
			@Param("now") Instant now);

	@Query("""
			select m from UserMuteJpaEntity m
			where m.muterId = :muterId and (m.expiresAt is null or m.expiresAt > :now)
			order by m.createdAt desc""")
	List<UserMuteJpaEntity> findActiveByMuterId(@Param("muterId") String muterId, @Param("now") Instant now);
}
