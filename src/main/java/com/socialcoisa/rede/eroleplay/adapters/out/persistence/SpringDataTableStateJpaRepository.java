package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataTableStateJpaRepository extends JpaRepository<TableStateJpaEntity, String> {

	Optional<TableStateJpaEntity> findByTableId(String tableId);
}
