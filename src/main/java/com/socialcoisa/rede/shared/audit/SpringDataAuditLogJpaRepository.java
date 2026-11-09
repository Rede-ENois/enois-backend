package com.socialcoisa.rede.shared.audit;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataAuditLogJpaRepository extends JpaRepository<AuditLogJpaEntity, String> {

	/**
	 * Ids das linhas mais antigas que o corte, para a retenção apagar UM lote por rodada.
	 *
	 * <p>{@code order by createdAt} com lote limitado não é estética: o índice
	 * ({@code idx_audit_created}, V261) entrega a faixa já em ordem, então a consulta para no lote
	 * em vez de ordenar a tabela. Sem o índice, a versão medida lia 400 mil linhas por rodada.</p>
	 */
	@Query("SELECT a.id FROM AuditLogJpaEntity a WHERE a.createdAt < :cutoff ORDER BY a.createdAt ASC")
	List<String> findIdsByCreatedAtBefore(@Param("cutoff") Instant cutoff, Pageable pageable);

	/** Apaga pelos ids já lidos; um delete só, sem carregar as entidades antes. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from AuditLogJpaEntity a where a.id in :ids")
	int deleteByIdIn(@Param("ids") List<String> ids);

	long countByCreatedAtBefore(Instant cutoff);
}
