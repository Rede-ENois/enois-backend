package com.socialcoisa.rede.idempotency.adapters.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataIdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, Long> {

	Optional<IdempotencyKeyEntity> findByUserIdAndMethodAndRouteAndIdempotencyKey(String userId, String method,
			String route, String idempotencyKey);
}
