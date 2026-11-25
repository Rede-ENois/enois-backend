package com.socialcoisa.rede.figurinhas.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataFigurinhaEventoJpaRepository extends JpaRepository<FigurinhaEventoJpaEntity, String> {

	List<FigurinhaEventoJpaEntity> findByFigurinhaIdOrderByCriadoEmAsc(String figurinhaId);
}
