package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataTableSeatJpaRepository extends JpaRepository<TableSeatJpaEntity, String> {

	Optional<TableSeatJpaEntity> findByTableIdAndUserId(String tableId, String userId);

	List<TableSeatJpaEntity> findByTableIdOrderBySeatedAtAsc(String tableId);
}
