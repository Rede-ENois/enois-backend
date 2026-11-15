package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataTableTokenJpaRepository extends JpaRepository<TableTokenJpaEntity, String> {

	List<TableTokenJpaEntity> findByTableIdOrderByUpdatedAtDesc(String tableId);
}
