package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Leitura do atlas. A busca por texto é feita no serviço, sobre o atlas recente — `lower()`/
 * `concat()` de JPQL não aceita a coluna de corpo (CLOB/longtext), e o teto do atlas é o
 * contrato de leitura (o front pagina por atualização).
 */
interface SpringDataWorldEntryJpaRepository extends JpaRepository<WorldEntryJpaEntity, String> {

	List<WorldEntryJpaEntity> findByCampaignIdOrderByUpdatedAtDesc(String campaignId, Pageable pageable);
}
