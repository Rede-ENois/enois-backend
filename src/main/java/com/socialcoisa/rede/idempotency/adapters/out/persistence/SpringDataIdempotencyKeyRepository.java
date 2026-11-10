package com.socialcoisa.rede.idempotency.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataIdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, Long> {

	Optional<IdempotencyKeyEntity> findByUserIdAndMethodAndRouteAndIdempotencyKey(String userId, String method,
			String route, String idempotencyKey);

	/** Ids de chaves expiradas, em lote — a retencao apaga por ids porque o H2 recusa LIMIT em DELETE. */
	@Query("select k.id from IdempotencyKeyEntity k where k.expiresAt < :cutoff")
	List<Long> findIdsByExpiresAtBefore(@Param("cutoff") Instant cutoff, Pageable pageable);

	@Modifying
	int deleteByIdIn(List<Long> ids);

	/** Quantas chaves expiradas ainda estao na tabela (diagnostico e teste). */
	long countByExpiresAtBefore(Instant cutoff);
}
