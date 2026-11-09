package com.socialcoisa.rede.ops.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataEmailLogJpaRepository extends JpaRepository<EmailLogJpaEntity, String> {

	List<EmailLogJpaEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

	/** Ids das linhas mais velhas que a janela, em lote — a limpeza apaga por ids. */
	@Query("select l.id from EmailLogJpaEntity l where l.createdAt < :cutoff")
	List<String> findIdsByCreatedAtBefore(@Param("cutoff") Instant cutoff, Pageable pageable);

	@Modifying
	int deleteByIdIn(List<String> ids);

	/** Quantas linhas estao elegiveis agora, sem apagar nada (diagnostico e teste). */
	long countByCreatedAtBefore(Instant cutoff);
}
